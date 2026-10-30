[CmdletBinding()]
param(
    [switch] $Execute
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:BackendRoot = Join-Path $script:RepoRoot 'backend'
$script:EnvPath = Join-Path $script:BackendRoot '.env.local'
$script:BaseUrl = 'http://127.0.0.1:8081'
$script:MysqlExe = Join-Path $HOME 'mysql\mysql-8.4.13\bin\mysql.exe'
$script:RunId = [Guid]::NewGuid().ToString('N')
$script:Settings = $null
$script:CreatedUsers = @()
$script:CreatedGroups = @()
$script:CreatedExpenses = @()
$script:AllowedRequestIds = @()
$script:Jobs = @()

function Load-Settings {
    if (-not (Test-Path -LiteralPath $script:EnvPath)) {
        throw 'Missing backend/.env.local.'
    }

    $settings = @{}
    foreach ($line in Get-Content -LiteralPath $script:EnvPath) {
        if ($line -match '^\s*([^#=]+)=(.*)$') {
            $settings[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
    foreach ($name in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD')) {
        if (-not $settings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($settings[$name])) {
            throw "Missing $name in backend/.env.local."
        }
    }
    return $settings
}

function Get-DatabaseUri {
    $uri = [Uri]($script:Settings['DB_URL'] -replace '^jdbc:', '')
    if ($uri.Scheme -ne 'mysql' -or $uri.Host -ne '127.0.0.1' -or $uri.Port -ne 3306 -or
            $uri.AbsolutePath.Trim('/').Split('?')[0] -ne 'splitsmart_dev') {
        throw 'Database identity does not match the expected local SplitSmart database.'
    }
    return $uri
}

function Invoke-MySql {
    param(
        [Parameter(Mandatory = $true)] [string] $Sql,
        [switch] $AllowMutation
    )
    if (-not $AllowMutation -and $Sql -match '^\s*(INSERT|UPDATE|DELETE|REPLACE|DROP|TRUNCATE|ALTER|CREATE)\b') {
        throw 'A mutating SQL statement was blocked outside the cleanup path.'
    }

    $uri = Get-DatabaseUri
    $databaseName = $uri.AbsolutePath.Trim('/').Split('?')[0]
    $previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    try {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $script:Settings['DB_PASSWORD'], 'Process')
        $output = & $script:MysqlExe --no-defaults --protocol=TCP `
            "--host=$($uri.Host)" `
            "--port=$($uri.Port)" `
            "--user=$($script:Settings['DB_USERNAME'])" `
            "--database=$databaseName" `
            --batch --raw --skip-column-names "--execute=$Sql" 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw 'MySQL query failed; no credential or SQL text was emitted.'
        }
        return @($output | ForEach-Object { [string]$_ })
    }
    finally {
        [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPassword, 'Process')
    }
}

function Get-MySqlScalar {
    param([Parameter(Mandatory = $true)] [string] $Sql)
    $rows = @(Invoke-MySql -Sql $Sql)
    if ($rows.Count -ne 1) {
        throw 'Expected one row from a read-only database check.'
    }
    return [string]$rows[0]
}

function Get-IdList {
    param([object[]] $Rows)
    $ids = @()
    foreach ($row in $Rows) {
        $id = [long]$row.Id
        if ($id -le 0) { throw 'A database row had an invalid primary key.' }
        $ids += $id
    }
    return ($ids -join ',')
}

function Get-RunNames {
    return @(
        "QA-Group-$script:RunId",
        "QA-Duplicate-$script:RunId",
        "QA-Race-$script:RunId"
    )
}

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)] [string] $Method,
        [Parameter(Mandatory = $true)] [string] $Path,
        [string] $Token,
        [object] $Body
    )

    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers['Authorization'] = "Bearer $Token"
    }
    $request = @{
        Uri = "$script:BaseUrl$Path"
        Method = $Method
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = 30
        ErrorAction = 'Stop'
    }
    if ($PSBoundParameters.ContainsKey('Body')) {
        $request['ContentType'] = 'application/json'
        $request['Body'] = ConvertTo-Json -InputObject $Body -Depth 10 -Compress
    }

    try {
        $response = Invoke-WebRequest @request
        $status = [int]$response.StatusCode
        $content = [string]$response.Content
    }
    catch {
        $web = $_.Exception.Response
        if ($null -eq $web) { throw }
        $reader = New-Object System.IO.StreamReader($web.GetResponseStream())
        try { $content = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $status = [int]$web.StatusCode
    }

    $json = $null
    if (-not [string]::IsNullOrWhiteSpace($content)) {
        try { $json = ConvertFrom-Json -InputObject $content } catch { }
    }
    return [pscustomobject]@{ StatusCode = $status; Json = $json }
}

function Assert-ApiProcess {
    $apiUri = [Uri]$script:BaseUrl
    $listeners = @(Get-NetTCPConnection -LocalPort $apiUri.Port -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -ne 1) {
        throw 'Could not establish a unique local backend listener identity.'
    }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listeners[0].OwningProcess)"
    if ($process.Name -ne 'java.exe' -or
            -not ([string]$process.CommandLine).Contains('com.splitsmart.SplitSmartApplication')) {
        throw 'The API listener is not a verified SplitSmart backend process.'
    }
    $health = Invoke-Api -Method 'GET' -Path '/api/health'
    if ($health.StatusCode -ne 200) { throw 'The verified SplitSmart backend health check failed.' }
    return $process
}

function Invoke-Preflight {
    $uri = Get-DatabaseUri
    if ($script:Settings['DB_USERNAME'] -ne 'splitsmart_app') {
        throw 'Database username does not match the configured SplitSmart application account.'
    }

    $identity = Get-MySqlScalar -Sql "SELECT CONCAT(DATABASE(), CHAR(9), SUBSTRING_INDEX(CURRENT_USER(), '@', 1), CHAR(9), VERSION())"
    $parts = $identity.Split("`t")
    if ($parts.Count -ne 3 -or $parts[0] -ne 'splitsmart_dev' -or
            $parts[1] -ne 'splitsmart_app' -or $parts[2] -notlike '8.4.*') {
        throw 'Authenticated database identity or version did not match the expected local instance.'
    }

    $foreignKeys = @(
        Invoke-MySql -Sql "SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME, '->', REFERENCED_TABLE_NAME, '.', REFERENCED_COLUMN_NAME) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='splitsmart_dev' AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY TABLE_NAME, COLUMN_NAME"
    )
    $expectedForeignKeys = @(
        'expense_groups.created_by->users.id',
        'expense_splits.expense_id->expenses.id',
        'expense_splits.user_id->users.id',
        'expenses.created_by_user_id->users.id',
        'expenses.group_id->expense_groups.id',
        'expenses.paid_by_user_id->users.id',
        'group_members.group_id->expense_groups.id',
        'group_members.user_id->users.id',
        'settlements.from_user_id->users.id',
        'settlements.group_id->expense_groups.id',
        'settlements.recorded_by_user_id->users.id',
        'settlements.to_user_id->users.id'
    )
    if ($foreignKeys.Count -ne $expectedForeignKeys.Count -or
            (Compare-Object ($foreignKeys | Sort-Object) ($expectedForeignKeys | Sort-Object))) {
        throw 'Database foreign-key topology differs from the verified cleanup plan.'
    }

    $mysqlListeners = @(Get-NetTCPConnection -LocalPort ([int]$uri.Port) -State Listen -ErrorAction SilentlyContinue)
    if ($mysqlListeners.Count -ne 1) {
        throw 'Could not establish a unique local MySQL listener identity.'
    }
    $mysqlProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($mysqlListeners[0].OwningProcess)"
    $mysqlCommand = [string]$mysqlProcess.CommandLine
    if ($mysqlProcess.Name -ne 'mysqld.exe' -or
            $mysqlProcess.ExecutablePath -ne (Join-Path $HOME 'mysql\mysql-8.4.13\bin\mysqld.exe') -or
            $mysqlCommand -notmatch 'mysql[\\/]+splitsmart[\\/]+my\.ini') {
        throw 'MySQL listener is not the verified portable SplitSmart instance.'
    }

    $apiProcess = Assert-ApiProcess
    $names = @(Get-RunNames)
    $nameLiterals = ($names | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ','
    $counts = [ordered]@{}
    $counts.Users = [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM users WHERE email LIKE 'qa-%-$script:RunId-%@example.com'")
    $counts.Groups = [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM expense_groups WHERE name IN ($nameLiterals)")
    $counts.Memberships = [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM group_members gm JOIN expense_groups g ON g.id = gm.group_id WHERE g.name IN ($nameLiterals)")
    $counts.Expenses = [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM expenses e JOIN expense_groups g ON g.id = e.group_id WHERE g.name IN ($nameLiterals)")
    $counts.Splits = [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM expense_splits s JOIN expenses e ON e.id = s.expense_id JOIN expense_groups g ON g.id = e.group_id WHERE g.name IN ($nameLiterals)")
    $counts.Settlements = [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM settlements s JOIN expense_groups g ON g.id = s.group_id WHERE g.name IN ($nameLiterals)")
    if (($counts.Values | Measure-Object -Sum).Sum -ne 0) {
        throw 'The generated run ID already owns database rows; no test or cleanup was attempted.'
    }

    Write-Host 'Step 23 read-only preflight: PASS'
    Write-Host "Database: $($parts[0]); authenticated account: $($parts[1]); MySQL $($parts[2])"
    Write-Host "MySQL PID: $($mysqlProcess.ProcessId); verified SplitSmart backend PID: $($apiProcess.ProcessId)"
    Write-Host "Run ID: $script:RunId; rows already tagged to this exact run: users=$($counts.Users), groups=$($counts.Groups), memberships=$($counts.Memberships), expenses=$($counts.Expenses), splits=$($counts.Splits), settlements=$($counts.Settlements)"
    Write-Host "Foreign-key topology: $($foreignKeys.Count) expected constraints verified."
    Write-Host 'Planned cleanup is restricted to verified primary keys, in FK order: settlements, expense_splits, expenses, group_members, expense_groups, users.'
    Write-Host 'Preflight performed no API mutation, INSERT, UPDATE, DELETE, or DDL.'
}

function New-User {
    param(
        [Parameter(Mandatory = $true)] [string] $Name,
        [Parameter(Mandatory = $true)] [string] $Marker
    )
    $email = "qa-$Marker-$([Guid]::NewGuid().ToString('N').Substring(0, 8))@example.com"
    $passwordBytes = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($passwordBytes) } finally { $random.Dispose() }
    $password = 'Qa!7-' + [BitConverter]::ToString($passwordBytes).Replace('-', '')
    [Array]::Clear($passwordBytes, 0, $passwordBytes.Length)

    $register = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Body @{
        name = $Name
        email = $email
        password = $password
    }
    if ($register.StatusCode -ne 201 -or -not $register.Json.id) {
        throw "Registration failed for $Name (HTTP $($register.StatusCode))."
    }
    $user = [pscustomobject]@{
        Name = $Name
        Email = $email
        Id = [long]$register.Json.id
        Token = $null
    }
    $script:CreatedUsers += $user

    $login = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Body @{ email = $email; password = $password }
    if ($login.StatusCode -ne 200 -or [string]::IsNullOrWhiteSpace([string]$login.Json.accessToken)) {
        throw "Login failed for $Name (HTTP $($login.StatusCode))."
    }
    $user.Token = [string]$login.Json.accessToken
    $password = $null
    return $user
}

function New-Group {
    param(
        [Parameter(Mandatory = $true)] [pscustomobject] $Owner,
        [Parameter(Mandatory = $true)] [string] $Name
    )
    $response = Invoke-Api -Method 'POST' -Path '/api/groups' -Token $Owner.Token -Body @{ name = $Name }
    if ($response.StatusCode -ne 201 -or -not $response.Json.id) {
        throw "Group creation failed (HTTP $($response.StatusCode))."
    }
    $group = [pscustomobject]@{ Id = [long]$response.Json.id; Name = $Name; OwnerId = $Owner.Id }
    $script:CreatedGroups += $group
    return $group
}

function Add-Member {
    param(
        [Parameter(Mandatory = $true)] [pscustomobject] $Owner,
        [Parameter(Mandatory = $true)] [pscustomobject] $Group,
        [Parameter(Mandatory = $true)] [pscustomobject] $Member
    )
    $response = Invoke-Api -Method 'POST' -Path "/api/groups/$($Group.Id)/members" -Token $Owner.Token -Body @{ email = $Member.Email }
    if ($response.StatusCode -ne 201) {
        throw "Add-member request failed (HTTP $($response.StatusCode))."
    }
}

function Create-Expense {
    param(
        [Parameter(Mandatory = $true)] [pscustomobject] $Owner,
        [Parameter(Mandatory = $true)] [pscustomobject] $Group,
        [Parameter(Mandatory = $true)] [object[]] $Participants
    )
    $payload = @{
        description = 'QA-900-generic'
        amount = [decimal]900
        paidByUserId = [long]$Owner.Id
        splitType = 'EQUAL'
        participantUserIds = @($Participants | ForEach-Object { [long]$_.Id })
    }
    $response = Invoke-Api -Method 'POST' -Path "/api/groups/$($Group.Id)/expenses" -Token $Owner.Token -Body $payload
    if ($response.StatusCode -ne 201 -or -not $response.Json.id) {
        throw "Expense creation failed (HTTP $($response.StatusCode))."
    }
    $expense = [pscustomobject]@{ Id = [long]$response.Json.id; GroupId = $Group.Id }
    $script:CreatedExpenses += $expense
    return $expense
}

function Get-Balances {
    param(
        [Parameter(Mandatory = $true)] [pscustomobject] $User,
        [Parameter(Mandatory = $true)] [pscustomobject] $Group
    )
    $response = Invoke-Api -Method 'GET' -Path "/api/groups/$($Group.Id)/balances" -Token $User.Token
    if ($response.StatusCode -ne 200) {
        throw "Balance request failed (HTTP $($response.StatusCode))."
    }
    return $response.Json
}

function Assert-Condition {
    param(
        [Parameter(Mandatory = $true)] [bool] $Condition,
        [Parameter(Mandatory = $true)] [string] $Message
    )
    if (-not $Condition) { throw $Message }
}

function Start-ApiJob {
    param(
        [Parameter(Mandatory = $true)] [string] $Method,
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [string] $Token,
        [Parameter(Mandatory = $true)] [object] $Body
    )
    $job = Start-Job -ScriptBlock {
        param($RequestMethod, $RequestUri, $BearerToken, $RequestBody)
        $headers = @{ Authorization = "Bearer $BearerToken" }
        try {
            $response = Invoke-WebRequest -Uri $RequestUri -Method $RequestMethod -Headers $headers `
                -Body ($RequestBody | ConvertTo-Json -Depth 10 -Compress) `
                -ContentType 'application/json' -UseBasicParsing -TimeoutSec 30 -ErrorAction Stop
            [pscustomobject]@{ StatusCode = [int]$response.StatusCode; TransportError = $false }
        }
        catch {
            $web = $_.Exception.Response
            if ($null -eq $web) {
                [pscustomobject]@{ StatusCode = 0; TransportError = $true }
            }
            else {
                [pscustomobject]@{ StatusCode = [int]$web.StatusCode; TransportError = $false }
            }
        }
    } -ArgumentList $Method, "$script:BaseUrl$Path", $Token, $Body
    $script:Jobs += $job
    return $job
}

function Receive-ApiJobs {
    param([object[]] $Jobs)
    $results = @()
    foreach ($job in $Jobs) {
        $output = @(Receive-Job -Job $job -Wait -ErrorAction Stop)
        if ($output.Count -ne 1) { throw 'A concurrent API job did not return exactly one result.' }
        $results += $output[0]
    }
    return ,$results
}

function Get-RunInventory {
    $names = @(Get-RunNames)
    $nameLiterals = ($names | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ','
    $users = @()
    $userPattern = "^qa-([A-D])-$script:RunId-[0-9a-f]{8}@example\.com$"
    foreach ($line in @(Invoke-MySql -Sql "SELECT id, name, email FROM users WHERE email LIKE 'qa-%-$script:RunId-%@example.com' ORDER BY id")) {
        $columns = $line -split "`t", 3
        if ($columns.Count -ne 3 -or $columns[2] -cnotmatch $userPattern) {
            throw 'A user row did not match this run exact email marker; cleanup was aborted.'
        }
        $letter = [regex]::Match($columns[2], $userPattern).Groups[1].Value
        if ($columns[1] -cne "QA User $letter") {
            throw 'A user row did not match this run expected identity; cleanup was aborted.'
        }
        $users += [pscustomobject]@{ Id = [long]$columns[0]; Name = $columns[1]; Email = $columns[2] }
    }
    $userIds = Get-IdList -Rows $users
    if ($users.Count -gt 4) { throw 'Unexpected user rows matched this run; cleanup was aborted.' }

    $groups = @()
    foreach ($line in @(Invoke-MySql -Sql "SELECT id, name, created_by FROM expense_groups WHERE name IN ($nameLiterals) ORDER BY id")) {
        $columns = $line -split "`t", 3
        if ($columns.Count -ne 3 -or $names -cnotcontains $columns[1]) {
            throw 'A group row did not match this run exact name; cleanup was aborted.'
        }
        $groups += [pscustomobject]@{ Id = [long]$columns[0]; Name = $columns[1]; OwnerId = [long]$columns[2] }
    }
    $groupIds = Get-IdList -Rows $groups
    if ($groups.Count -gt 3) { throw 'Unexpected group rows matched this run; cleanup was aborted.' }

    $owner = @($users | Where-Object { $_.Name -ceq 'QA User A' }) | Select-Object -First 1
    foreach ($group in $groups) {
        if ($null -eq $owner -or $group.OwnerId -ne $owner.Id) {
            throw 'A group owner did not match the current run user; cleanup was aborted.'
        }
    }

    $members = @()
    if ($groups.Count -gt 0) {
        foreach ($line in @(Invoke-MySql -Sql "SELECT id, group_id, user_id FROM group_members WHERE group_id IN ($groupIds) ORDER BY id")) {
            $columns = $line -split "`t", 3
            if ($columns.Count -ne 3) { throw 'Could not identify group-member row ownership.' }
            $members += [pscustomobject]@{ Id = [long]$columns[0]; GroupId = [long]$columns[1]; UserId = [long]$columns[2] }
        }
    }
    foreach ($member in $members) {
        if (($groups.Id -notcontains $member.GroupId) -or ($users.Id -notcontains $member.UserId)) {
            throw 'A group membership references a row outside this run; cleanup was aborted.'
        }
    }

    $expenses = @()
    if ($groups.Count -gt 0) {
        foreach ($line in @(Invoke-MySql -Sql "SELECT id, group_id, paid_by_user_id, created_by_user_id FROM expenses WHERE group_id IN ($groupIds) ORDER BY id")) {
            $columns = $line -split "`t", 4
            if ($columns.Count -ne 4) { throw 'Could not identify expense ownership.' }
            $expenses += [pscustomobject]@{
                Id = [long]$columns[0]
                GroupId = [long]$columns[1]
                PaidByUserId = [long]$columns[2]
                CreatedByUserId = [long]$columns[3]
            }
        }
    }
    $expenseIds = Get-IdList -Rows $expenses
    foreach ($expense in $expenses) {
        if (($groups.Id -notcontains $expense.GroupId) -or ($users.Id -notcontains $expense.PaidByUserId) -or
                ($users.Id -notcontains $expense.CreatedByUserId)) {
            throw 'An expense references a row outside this run; cleanup was aborted.'
        }
    }

    $splits = @()
    if ($expenses.Count -gt 0) {
        foreach ($line in @(Invoke-MySql -Sql "SELECT id, expense_id, user_id FROM expense_splits WHERE expense_id IN ($expenseIds) ORDER BY id")) {
            $columns = $line -split "`t", 3
            if ($columns.Count -ne 3) { throw 'Could not identify expense-split ownership.' }
            $splits += [pscustomobject]@{ Id = [long]$columns[0]; ExpenseId = [long]$columns[1]; UserId = [long]$columns[2] }
        }
    }
    foreach ($split in $splits) {
        if (($expenses.Id -notcontains $split.ExpenseId) -or ($users.Id -notcontains $split.UserId)) {
            throw 'An expense split references a row outside this run; cleanup was aborted.'
        }
    }

    $settlements = @()
    if ($groups.Count -gt 0) {
        foreach ($line in @(Invoke-MySql -Sql "SELECT id, group_id, from_user_id, to_user_id, recorded_by_user_id, request_id FROM settlements WHERE group_id IN ($groupIds) ORDER BY id")) {
            $columns = $line -split "`t", 6
            if ($columns.Count -ne 6) { throw 'Could not identify settlement ownership.' }
            $settlements += [pscustomobject]@{
                Id = [long]$columns[0]
                GroupId = [long]$columns[1]
                FromUserId = [long]$columns[2]
                ToUserId = [long]$columns[3]
                RecordedByUserId = [long]$columns[4]
                RequestId = $columns[5]
            }
        }
    }
    foreach ($settlement in $settlements) {
        if (($groups.Id -notcontains $settlement.GroupId) -or
                ($users.Id -notcontains $settlement.FromUserId) -or
                ($users.Id -notcontains $settlement.ToUserId) -or
                ($users.Id -notcontains $settlement.RecordedByUserId) -or
                ($script:AllowedRequestIds -cnotcontains $settlement.RequestId)) {
            throw 'A settlement does not match this run exact group, users, and request ID; cleanup was aborted.'
        }
    }

    if ($users.Count -gt 0) {
        $allGroups = if ($groups.Count -gt 0) { $groupIds } else { '0' }
        $allExpenses = if ($expenses.Count -gt 0) { $expenseIds } else { '0' }
        $externalReferences = @(
            [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM group_members WHERE user_id IN ($userIds) AND group_id NOT IN ($allGroups)")
            [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM expense_groups WHERE created_by IN ($userIds) AND id NOT IN ($allGroups)")
            [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM expenses WHERE (paid_by_user_id IN ($userIds) OR created_by_user_id IN ($userIds)) AND group_id NOT IN ($allGroups)")
            [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM expense_splits WHERE user_id IN ($userIds) AND expense_id NOT IN ($allExpenses)")
            [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM settlements WHERE (from_user_id IN ($userIds) OR to_user_id IN ($userIds) OR recorded_by_user_id IN ($userIds)) AND group_id NOT IN ($allGroups)")
        )
        if (($externalReferences | Measure-Object -Sum).Sum -ne 0) {
            throw 'A current-run test user has dependencies outside current-run groups; cleanup was aborted.'
        }
    }

    return [pscustomobject]@{
        Users = $users
        Groups = $groups
        Members = $members
        Expenses = $expenses
        Splits = $splits
        Settlements = $settlements
    }
}

function Remove-RunRows {
    param(
        [Parameter(Mandatory = $true)] [string] $Table,
        [Parameter(Mandatory = $true)] [object[]] $Rows
    )
    if ($Rows.Count -eq 0) { return }
    $ids = Get-IdList -Rows $Rows
    $before = [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM $Table WHERE id IN ($ids)")
    if ($before -ne $Rows.Count) {
        throw "Ownership count mismatch for $Table; no further cleanup was attempted."
    }
    $null = Invoke-MySql -Sql "DELETE FROM $Table WHERE id IN ($ids)" -AllowMutation
    $after = [long](Get-MySqlScalar -Sql "SELECT COUNT(*) FROM $Table WHERE id IN ($ids)")
    if ($after -ne 0) { throw "Verified cleanup did not remove the exact $Table row set." }
    Write-Host "Cleanup verified: $Table rows removed=$before"
}

function Invoke-RunCleanup {
    $inventory = Get-RunInventory
    Remove-RunRows -Table 'settlements' -Rows $inventory.Settlements
    Remove-RunRows -Table 'expense_splits' -Rows $inventory.Splits
    Remove-RunRows -Table 'expenses' -Rows $inventory.Expenses
    Remove-RunRows -Table 'group_members' -Rows $inventory.Members
    Remove-RunRows -Table 'expense_groups' -Rows $inventory.Groups
    Remove-RunRows -Table 'users' -Rows $inventory.Users
    Write-Host 'Current-run cleanup complete; no prefix-wide cleanup was performed.'
}

function Invoke-RunTests {
    $users = @{
        A = New-User -Name 'QA User A' -Marker "A-$script:RunId"
        B = New-User -Name 'QA User B' -Marker "B-$script:RunId"
        C = New-User -Name 'QA User C' -Marker "C-$script:RunId"
        D = New-User -Name 'QA User D' -Marker "D-$script:RunId"
    }

    $group = New-Group -Owner $users.A -Name "QA-Group-$script:RunId"
    Add-Member -Owner $users.A -Group $group -Member $users.B
    Add-Member -Owner $users.A -Group $group -Member $users.C
    $null = Create-Expense -Owner $users.A -Group $group -Participants @($users.A, $users.B, $users.C)
    $balances = Get-Balances -User $users.A -Group $group
    $expectedBalances = @(
        @{ UserId = $users.A.Id; Amount = [decimal]600 },
        @{ UserId = $users.B.Id; Amount = [decimal]-300 },
        @{ UserId = $users.C.Id; Amount = [decimal]-300 }
    )
    foreach ($expected in $expectedBalances) {
        $match = @($balances.balances | Where-Object { [long]$_.userId -eq $expected.UserId }) | Select-Object -First 1
        Assert-Condition ($null -ne $match -and [decimal]$match.netBalance -eq $expected.Amount) 'Initial expense balances did not match the expected exact amounts.'
    }

    $firstRequestId = [Guid]::NewGuid().ToString()
    $secondRequestId = [Guid]::NewGuid().ToString()
    $script:AllowedRequestIds += @($firstRequestId, $secondRequestId)
    $firstGroupJobs = @(
        (Start-ApiJob -Method 'POST' -Path "/api/groups/$($group.Id)/settlements" -Token $users.B.Token -Body @{
            fromUserId = $users.B.Id; toUserId = $users.A.Id; amount = [decimal]75
            requestId = $firstRequestId; note = 'Concurrent settlement A'
        }),
        (Start-ApiJob -Method 'POST' -Path "/api/groups/$($group.Id)/settlements" -Token $users.B.Token -Body @{
            fromUserId = $users.B.Id; toUserId = $users.A.Id; amount = [decimal]75
            requestId = $secondRequestId; note = 'Concurrent settlement B'
        })
    )
    $firstResults = @(Receive-ApiJobs -Jobs $firstGroupJobs)
    Assert-Condition ($firstResults.Count -eq 2 -and @($firstResults | Where-Object { $_.TransportError }).Count -eq 0) 'Concurrent distinct settlement requests did not both receive HTTP responses.'
    Assert-Condition (@($firstResults | Where-Object { $_.StatusCode -eq 201 }).Count -eq 1) 'Concurrent distinct settlement requests did not produce exactly one success.'
    $settlements = Invoke-Api -Method 'GET' -Path "/api/groups/$($group.Id)/settlements" -Token $users.A.Token
    Assert-Condition ($settlements.StatusCode -eq 200) 'Settlement list fetch failed after concurrent requests.'
    Assert-Condition (@($settlements.Json | Where-Object { [decimal]$_.amount -eq 75 }).Count -eq 1) 'Exactly one INR 75 settlement should exist after concurrent requests.'
    $finalBalances = Get-Balances -User $users.A -Group $group
    $senderBalance = [decimal](@($finalBalances.balances | Where-Object { [long]$_.userId -eq $users.B.Id })[0].netBalance)
    $receiverBalance = [decimal](@($finalBalances.balances | Where-Object { [long]$_.userId -eq $users.A.Id })[0].netBalance)
    Assert-Condition ($senderBalance -eq -225 -and $receiverBalance -eq 525) 'Balances after the accepted settlement were incorrect.'

    $duplicateGroup = New-Group -Owner $users.A -Name "QA-Duplicate-$script:RunId"
    Add-Member -Owner $users.A -Group $duplicateGroup -Member $users.B
    $null = Create-Expense -Owner $users.A -Group $duplicateGroup -Participants @($users.A, $users.B)
    $duplicateRequestId = [Guid]::NewGuid().ToString()
    $script:AllowedRequestIds += $duplicateRequestId
    $duplicateJobs = @(
        (Start-ApiJob -Method 'POST' -Path "/api/groups/$($duplicateGroup.Id)/settlements" -Token $users.B.Token -Body @{
            fromUserId = $users.B.Id; toUserId = $users.A.Id; amount = [decimal]10
            requestId = $duplicateRequestId; note = 'Duplicate race A'
        }),
        (Start-ApiJob -Method 'POST' -Path "/api/groups/$($duplicateGroup.Id)/settlements" -Token $users.B.Token -Body @{
            fromUserId = $users.B.Id; toUserId = $users.A.Id; amount = [decimal]10
            requestId = $duplicateRequestId; note = 'Duplicate race B'
        })
    )
    $duplicateResults = @(Receive-ApiJobs -Jobs $duplicateJobs)
    Assert-Condition (@($duplicateResults | Where-Object { -not $_.TransportError -and $_.StatusCode -eq 201 }).Count -eq 1) 'Duplicate requestId race did not produce exactly one created settlement.'
    Assert-Condition (@($duplicateResults | Where-Object { -not $_.TransportError -and $_.StatusCode -eq 409 }).Count -eq 1) 'Duplicate requestId race did not reject exactly one request.'
    $duplicateList = Invoke-Api -Method 'GET' -Path "/api/groups/$($duplicateGroup.Id)/settlements" -Token $users.A.Token
    Assert-Condition ($duplicateList.StatusCode -eq 200) 'Duplicate-race settlement list fetch failed.'
    Assert-Condition (@($duplicateList.Json | Where-Object { $_.requestId -eq $duplicateRequestId }).Count -eq 1) 'Duplicate requestId was persisted more than once.'

    $raceGroup = New-Group -Owner $users.A -Name "QA-Race-$script:RunId"
    Add-Member -Owner $users.A -Group $raceGroup -Member $users.B
    $raceExpense = Create-Expense -Owner $users.A -Group $raceGroup -Participants @($users.A, $users.B)
    $raceRequestId = [Guid]::NewGuid().ToString()
    $script:AllowedRequestIds += $raceRequestId
    $settlementJob = Start-ApiJob -Method 'POST' -Path "/api/groups/$($raceGroup.Id)/settlements" -Token $users.B.Token -Body @{
        fromUserId = $users.B.Id; toUserId = $users.A.Id; amount = [decimal]50
        requestId = $raceRequestId; note = 'Race settlement'
    }
    $updateJob = Start-ApiJob -Method 'PUT' -Path "/api/groups/$($raceGroup.Id)/expenses/$($raceExpense.Id)" -Token $users.A.Token -Body @{
        description = 'Race updated expense'
        amount = [decimal]200
        paidByUserId = $users.A.Id
        splitType = 'EQUAL'
        participantUserIds = @($users.A.Id, $users.B.Id)
    }
    $raceResults = @(Receive-ApiJobs -Jobs @($settlementJob, $updateJob))
    Assert-Condition (@($raceResults | Where-Object { $_.TransportError -or $_.StatusCode -notin @(200, 201) }).Count -eq 0) 'Expense-update/settlement race did not complete with valid HTTP responses.'
    $raceSettlementList = Invoke-Api -Method 'GET' -Path "/api/groups/$($raceGroup.Id)/settlements" -Token $users.A.Token
    Assert-Condition ($raceSettlementList.StatusCode -eq 200) 'Settlement list fetch failed after the expense race.'
    $raceBalances = Get-Balances -User $users.A -Group $raceGroup
    if ($raceBalances.balances.Count -ne 2) { throw 'Expense race returned an unexpected number of group balances.' }
}

$script:Settings = Load-Settings
Invoke-Preflight
if (-not $Execute) {
    Write-Host 'Dry run only. Pass -Execute to run the QA scenario and its exact-ID finally cleanup.'
    return
}

$runError = $null
$cleanupError = $null
try {
    Invoke-RunTests
}
catch {
    $runError = $_
}
finally {
    foreach ($job in $script:Jobs) {
        if ($job.State -in @('Running', 'NotStarted')) {
            Stop-Job -Job $job -ErrorAction SilentlyContinue
        }
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
    try {
        Invoke-RunCleanup
    }
    catch {
        $cleanupError = $_
    }
}

if ($null -ne $runError) {
    if ($null -ne $cleanupError) {
        throw "Step 23 verification failed and exact-ID cleanup was blocked: $($cleanupError.Exception.Message)"
    }
    throw "Step 23 verification failed; exact-ID cleanup completed: $($runError.Exception.Message)"
}
if ($null -ne $cleanupError) {
    throw "Step 23 verification passed, but exact-ID cleanup was blocked: $($cleanupError.Exception.Message)"
}
Write-Host 'Completed Step 23 verification and verified exact-ID cleanup.'
