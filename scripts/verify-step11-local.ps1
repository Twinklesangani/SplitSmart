[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:Failures = 0
$script:Passes = 0
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:BackendRoot = Join-Path $script:RepoRoot 'backend'
$script:BaseUrl = 'http://127.0.0.1:8081'
$script:MysqlRoot = Join-Path $HOME 'mysql\mysql-8.4.13'
$script:MysqlExe = Join-Path $script:MysqlRoot 'bin\mysql.exe'
$script:MysqldExe = Join-Path $script:MysqlRoot 'bin\mysqld.exe'
$script:MysqlAdminExe = Join-Path $script:MysqlRoot 'bin\mysqladmin.exe'
$script:MysqlConfig = Join-Path $HOME 'mysql\splitsmart\my.ini'
$script:DataDirectory = Join-Path $HOME 'mysql\splitsmart\data'
$script:JavaHome = Join-Path $HOME '.jdks\temurin-21'
$script:JavaExe = Join-Path $script:JavaHome 'bin\java.exe'
$script:JarPath = Join-Path $script:BackendRoot 'target\splitsmart-backend-0.0.1-SNAPSHOT.jar'
$script:EnvPath = Join-Path $script:BackendRoot '.env.local'
$script:VerificationId = [Guid]::NewGuid().ToString('N')
$script:EmailPrefix = "step11-$($script:VerificationId)"
$script:GroupName = "Step11-$($script:VerificationId)"
$script:Users = @{}
$script:IdentityEmails = @{}
$script:IdentityNames = @{}
$script:Tokens = @{}
$script:GroupMemberRoles = @()
$script:GroupId = $null
$script:ExpenseId = $null
$script:AppProcessId = $null
$script:AppStdout = Join-Path $env:TEMP "splitsmart-step11-app-$($script:VerificationId).out.log"
$script:AppStderr = Join-Path $env:TEMP "splitsmart-step11-app-$($script:VerificationId).err.log"
$script:OwnedMysqlRootPid = $null
$script:OwnedMysqlListenerPid = $null
$script:OriginalEnvironment = @{}
$script:ConnectionVerified = $false
$script:CleanupSafe = $true
$script:CurrentStage = 'initialization'

function Write-Check {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Passed,
        [string]$Detail = ''
    )

    if ($Passed) {
        $script:Passes++
        Write-Host "PASS: $Name"
    }
    else {
        $script:Failures++
        Write-Host "FAIL: $Name$(if ($Detail) { " - $Detail" })" -ForegroundColor Red
    }
}

function Stop-WithCheck {
    param([Parameter(Mandatory = $true)][string]$Name)

    Write-Check -Name $Name -Passed $false
    throw "Verification stopped: $Name"
}

function Read-LocalSettings {
    if (-not (Test-Path -LiteralPath $script:EnvPath -PathType Leaf)) {
        Stop-WithCheck 'ignored backend/.env.local exists'
    }

    $values = @{}
    foreach ($line in Get-Content -LiteralPath $script:EnvPath) {
        if ($line -match '^\s*([^#=]+)=(.*)$') {
            $values[$matches[1].Trim()] = $matches[2]
        }
    }

    foreach ($key in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET')) {
        if (-not $values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($values[$key])) {
            Stop-WithCheck "local setting $key is present"
        }
    }

    $url = [regex]::Match(
        $values['DB_URL'],
        '^jdbc:mysql://(?<host>[^/:?]+)(:(?<port>\d+))?/(?<schema>[^?;]+)')
    if (-not $url.Success -or
        $url.Groups['host'].Value -notin @('localhost', '127.0.0.1') -or
        ($url.Groups['port'].Success -and $url.Groups['port'].Value -ne '3306') -or
        $url.Groups['schema'].Value -ne 'splitsmart_dev' -or
        $values['DB_USERNAME'] -ne 'splitsmart_app') {
        Stop-WithCheck 'database settings target the expected localhost SplitSmart account and schema'
    }

    foreach ($key in $values.Keys) {
        $script:OriginalEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
    }
    $script:OriginalEnvironment['MYSQL_PWD'] = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
    $env:MYSQL_PWD = $values['DB_PASSWORD']

    $script:DbSettings = @{
        Host = '127.0.0.1'
        Port = 3306
        Schema = 'splitsmart_dev'
        Username = 'splitsmart_app'
    }
    Write-Check 'local database settings are present and target localhost' $true
}

function Invoke-MySql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $output = & $script:MysqlExe `
        --no-defaults `
        --protocol=TCP `
        "--host=$($script:DbSettings.Host)" `
        "--port=$($script:DbSettings.Port)" `
        "--user=$($script:DbSettings.Username)" `
        "--database=$($script:DbSettings.Schema)" `
        --batch `
        --raw `
        --skip-column-names `
        "--execute=$Sql" 2>$null

    if ($LASTEXITCODE -ne 0) {
        throw 'MySQL query failed; client output was suppressed to avoid disclosing local details.'
    }
    return @($output | ForEach-Object { [string]$_ })
}

function Invoke-MySqlScalar {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $rows = @(Invoke-MySql -Sql $Sql)
    if ($rows.Count -ne 1) {
        throw 'Expected exactly one row from a local database verification query.'
    }
    return $rows[0]
}

function Get-ListenerProcesses {
    $listeners = @(Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue)
    $processes = @()
    foreach ($listener in $listeners) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" `
            -ErrorAction SilentlyContinue
        if ($process) {
            $processes += $process
        }
    }
    return @($processes | Sort-Object ProcessId -Unique)
}

function Test-ExpectedMysqlProcess {
    param([Parameter(Mandatory = $true)]$Process)

    $pathMatches = [string]::Equals(
        [IO.Path]::GetFullPath($Process.ExecutablePath),
        [IO.Path]::GetFullPath($script:MysqldExe),
        [StringComparison]::OrdinalIgnoreCase)
    $arguments = [string]$Process.CommandLine
    $configMatches = $arguments.IndexOf(
        [IO.Path]::GetFullPath($script:MysqlConfig),
        [StringComparison]::OrdinalIgnoreCase) -ge 0
    return $pathMatches -and $configMatches
}

function Start-ExistingMysqlIfNeeded {
    $listeners = @(Get-ListenerProcesses)
    if ($listeners.Count -gt 0) {
        if ($listeners.Count -ne 1 -or -not (Test-ExpectedMysqlProcess $listeners[0])) {
            Stop-WithCheck 'port 3306 belongs to the configured portable SplitSmart MySQL instance'
        }
        Write-Check 'existing MySQL listener is the configured SplitSmart instance' $true
        return
    }

    if (-not (Test-Path -LiteralPath $script:MysqldExe -PathType Leaf) -or
        -not (Test-Path -LiteralPath $script:MysqlConfig -PathType Leaf) -or
        -not (Test-Path -LiteralPath $script:DataDirectory -PathType Container)) {
        Stop-WithCheck 'existing portable MySQL binary, configuration and data directory are available'
    }

    $otherServers = @(Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" -ErrorAction SilentlyContinue)
    if ($otherServers.Count -gt 0) {
        Stop-WithCheck 'no unverified MySQL server process is running'
    }

    $started = Start-Process -FilePath $script:MysqldExe `
        -ArgumentList @("--defaults-file=`"$($script:MysqlConfig)`"", '--console') `
        -WorkingDirectory (Split-Path $script:MysqldExe) `
        -RedirectStandardOutput (Join-Path $env:TEMP "splitsmart-step11-mysql-$($script:VerificationId).out.log") `
        -RedirectStandardError (Join-Path $env:TEMP "splitsmart-step11-mysql-$($script:VerificationId).err.log") `
        -PassThru
    $script:OwnedMysqlRootPid = $started.Id

    $deadline = (Get-Date).AddSeconds(45)
    do {
        Start-Sleep -Seconds 1
        $listeners = @(Get-ListenerProcesses)
        if ($listeners.Count -gt 0) {
            break
        }
        $started.Refresh()
        if ($started.HasExited) {
            break
        }
    } while ((Get-Date) -lt $deadline)

    if ($listeners.Count -ne 1 -or -not (Test-ExpectedMysqlProcess $listeners[0])) {
        Stop-WithCheck 'script-started MySQL reached port 3306 using the configured instance'
    }
    $script:OwnedMysqlListenerPid = [int]$listeners[0].ProcessId
    $parentId = [int]$listeners[0].ParentProcessId
    if ($script:OwnedMysqlListenerPid -ne $script:OwnedMysqlRootPid -and
        $parentId -ne $script:OwnedMysqlRootPid) {
        Stop-WithCheck 'the MySQL listener is owned by the process started by this script'
    }
    Write-Check 'script-started MySQL reached the expected local listener' $true
}

function Remove-LegacyCredentialFiles {
    $directories = @(
        (Join-Path $env:SystemRoot 'Temp'),
        $env:TEMP
    ) | Select-Object -Unique
    $pattern = '^splitsmart-step11-client(?:-[a-f0-9]{32})?\.cnf$'

    foreach ($directory in $directories) {
        try {
            $matches = @(Get-ChildItem -LiteralPath $directory -File -ErrorAction Stop |
                Where-Object { $_.Name -match $pattern })
        }
        catch {
            Write-Host "WARN: Cannot inspect $directory for prior Step 11 client files: $($_.Exception.Message)"
            continue
        }
        foreach ($file in $matches) {
            try {
                Remove-Item -LiteralPath $file.FullName -ErrorAction Stop
                Write-Host 'PASS: removed a specifically named prior Step 11 temporary client file'
            }
            catch {
                Write-Host "WARN: Cannot remove specifically named prior Step 11 client file in $directory."
            }
        }
    }
}

function Confirm-DatabaseIdentity {
    $row = Invoke-MySqlScalar -Sql @"
SELECT CONCAT(VERSION(), CHAR(9), DATABASE(), CHAR(9), @@port, CHAR(9),
       @@bind_address, CHAR(9), @@datadir, CHAR(9), CURRENT_USER())
"@
    $columns = $row -split "`t", 6
    if ($columns.Count -ne 6 -or
        $columns[0] -notmatch '^8\.4\.13(?:-|$)' -or
        $columns[1] -ne 'splitsmart_dev' -or
        $columns[2] -ne '3306' -or
        $columns[3] -notin @('127.0.0.1', 'localhost') -or
        $columns[5] -notlike 'splitsmart_app@*') {
        Stop-WithCheck 'MySQL reports the expected local database, port, bind address and app account'
    }

    $expectedData = [IO.Path]::GetFullPath($script:DataDirectory).TrimEnd('\') + '\'
    $actualData = [IO.Path]::GetFullPath($columns[4].Replace('/', '\')).TrimEnd('\') + '\'
    if (-not [string]::Equals($expectedData, $actualData, [StringComparison]::OrdinalIgnoreCase)) {
        Stop-WithCheck 'MySQL uses the existing SplitSmart data directory'
    }
    Write-Check "MySQL $($columns[0]) is connected to splitsmart_dev on loopback" $true
    Write-Check 'MySQL data directory matches the existing configured directory' $true
    $script:ConnectionVerified = $true
}

function Confirm-MigrationHistoryBeforeStartup {
    $historyTable = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'
"@
    if ($historyTable -ne '1') {
        Stop-WithCheck 'Flyway V1 history table exists before backend startup'
    }

    $rows = @(Invoke-MySql -Sql @"
SELECT COALESCE(version, ''), success, type FROM flyway_schema_history
ORDER BY installed_rank
"@)
    if ($rows.Count -lt 1) {
        Stop-WithCheck 'Flyway history contains a successful V1 migration'
    }

    $versions = @{}
    foreach ($row in $rows) {
        $parts = $row -split "`t", 3
        if ($parts.Count -ne 3 -or $parts[1] -ne '1' -or
            $parts[2] -ne 'SQL' -or [string]::IsNullOrWhiteSpace($parts[0]) -or
            $versions.ContainsKey($parts[0])) {
            Stop-WithCheck 'Flyway history has only successful, unique versioned SQL migrations'
        }
        $versions[$parts[0]] = $true
    }
    $unexpectedVersions = @($versions.Keys | Where-Object { $_ -notin @('1', '2') })
    if (-not $versions.ContainsKey('1') -or $unexpectedVersions.Count -gt 0) {
        Stop-WithCheck 'Flyway history contains V1 and no unexpected migration versions'
    }

    $script:V2WasApplied = $versions.ContainsKey('2')
    if ($script:V2WasApplied) {
        Write-Check 'V1 and V2 are already successful; no other migrations are present' $true
    }
    else {
        Write-Check 'V1 is successful and V2 is the only expected pending migration' $true
    }
}

function Confirm-ExpenseSchema {
    $tables = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('expenses', 'expense_splits')
"@
    if ($tables -ne '2') {
        Stop-WithCheck 'expenses and expense_splits tables exist'
    }

    $expectedForeignKeys = @(
        'expenses.fk_expenses_group.group_id>expense_groups.id',
        'expenses.fk_expenses_paid_by_user.paid_by_user_id>users.id',
        'expenses.fk_expenses_created_by_user.created_by_user_id>users.id',
        'expense_splits.fk_expense_splits_expense.expense_id>expenses.id',
        'expense_splits.fk_expense_splits_user.user_id>users.id'
    )
    $foreignRows = @(Invoke-MySql -Sql @"
SELECT CONCAT(table_name, '.', constraint_name, '.', column_name, '>',
              referenced_table_name, '.', referenced_column_name)
FROM information_schema.key_column_usage
WHERE table_schema = DATABASE()
  AND table_name IN ('expenses', 'expense_splits')
  AND referenced_table_name IS NOT NULL
ORDER BY table_name, constraint_name
"@)
    foreach ($name in $expectedForeignKeys) {
        if ($foreignRows -notcontains $name) {
            Stop-WithCheck "expected foreign key $name exists"
        }
    }
    if ($foreignRows.Count -ne $expectedForeignKeys.Count) {
        Stop-WithCheck 'expense tables contain only the five expected foreign keys'
    }

    $uniqueIndex = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*) FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'expense_splits'
  AND index_name = 'uk_expense_splits_expense_user'
  AND non_unique = 0
  AND column_name IN ('expense_id', 'user_id')
"@
    if ($uniqueIndex -ne '2') {
        Stop-WithCheck 'expense split participant unique constraint covers expense_id and user_id'
    }
    $positiveChecks = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*) FROM information_schema.table_constraints
WHERE constraint_schema = DATABASE()
  AND constraint_type = 'CHECK'
  AND constraint_name IN ('chk_expenses_amount_positive', 'chk_expense_splits_amount_positive')
"@
    if ($positiveChecks -ne '2') {
        Stop-WithCheck 'positive amount check constraints exist on both expense tables'
    }
    Write-Check 'expense tables and expected foreign keys exist' $true
    Write-Check 'expense split unique constraint exists' $true
    Write-Check 'positive amount database constraints exist' $true
}

function Get-HttpResult {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$Token,
        [object]$Body
    )

    $headers = @{}
    if ($Token) {
        $headers['Authorization'] = "Bearer $Token"
    }
    $parameters = @{
        Uri = "$($script:BaseUrl)$Path"
        Method = $Method
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = 20
        ErrorAction = 'Stop'
    }
    if ($PSBoundParameters.ContainsKey('Body')) {
        $parameters['ContentType'] = 'application/json'
        $parameters['Body'] = ConvertTo-Json -InputObject $Body -Depth 10 -Compress
    }

    try {
        $response = Invoke-WebRequest @parameters
        return @{
            Status = [int]$response.StatusCode
            Body = [string]$response.Content
        }
    }
    catch {
        $response = $_.Exception.Response
        if ($response -and $response -is [System.Net.HttpWebResponse]) {
            $reader = New-Object IO.StreamReader($response.GetResponseStream())
            try {
                $content = $reader.ReadToEnd()
            }
            finally {
                $reader.Dispose()
            }
            return @{ Status = [int]$response.StatusCode; Body = $content }
        }
        throw
    }
}

function Assert-HttpStatus {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Result,
        [Parameter(Mandatory = $true)][int]$Expected,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if ($Result.Status -ne $Expected) {
        Write-Check -Name $Name -Passed $false -Detail "expected HTTP $Expected, received $($Result.Status)"
        throw "HTTP verification failed: $Name"
    }
    Write-Check -Name "$Name (HTTP $Expected)" -Passed $true
}

function Start-Backend {
    if (Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue) {
        Stop-WithCheck 'port 8081 is available; refusing to interfere with an existing application'
    }
    if (-not (Test-Path -LiteralPath $script:JarPath -PathType Leaf)) {
        Stop-WithCheck 'existing packaged backend JAR is present'
    }

    $process = Start-Process -FilePath $script:JavaExe `
        -ArgumentList @('-jar', "`"$($script:JarPath)`"", '--server.port=8081') `
        -WorkingDirectory $script:BackendRoot `
        -RedirectStandardOutput $script:AppStdout `
        -RedirectStandardError $script:AppStderr `
        -PassThru
    $script:AppProcessId = $process.Id

    $deadline = (Get-Date).AddSeconds(90)
    do {
        Start-Sleep -Seconds 2
        $process.Refresh()
        if ($process.HasExited) {
            Stop-WithCheck 'packaged Spring Boot backend starts successfully'
        }
        try {
            $health = Get-HttpResult -Method GET -Path '/api/health'
            if ($health.Status -eq 200) {
                Write-Check 'packaged Spring Boot backend is ready on port 8081' $true
                return
            }
        }
        catch {
        }
    } while ((Get-Date) -lt $deadline)

    Stop-WithCheck 'packaged Spring Boot backend becomes ready within 90 seconds'
}

function Stop-Backend {
    if (-not $script:AppProcessId) {
        return
    }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($script:AppProcessId)" `
        -ErrorAction SilentlyContinue
    if (-not $process) {
        $script:AppProcessId = $null
        return
    }
    $expectedJava = [string]::Equals(
        [IO.Path]::GetFullPath($process.ExecutablePath),
        [IO.Path]::GetFullPath($script:JavaExe),
        [StringComparison]::OrdinalIgnoreCase)
    $expectedJar = ([string]$process.CommandLine).IndexOf(
        [IO.Path]::GetFullPath($script:JarPath),
        [StringComparison]::OrdinalIgnoreCase) -ge 0
    if (-not $expectedJava -or -not $expectedJar) {
        Write-Check 'backend process ownership is verified before shutdown' $false
        return
    }
    # The API has no shutdown endpoint; stop only the verified process started here.
    Stop-Process -Id $script:AppProcessId -ErrorAction SilentlyContinue
    $deadline = (Get-Date).AddSeconds(15)
    do {
        Start-Sleep -Milliseconds 250
        $remaining = Get-CimInstance Win32_Process -Filter "ProcessId=$($script:AppProcessId)" `
            -ErrorAction SilentlyContinue
        if (-not $remaining) {
            $script:AppProcessId = $null
            return
        }
    } while ((Get-Date) -lt $deadline)
    Write-Check 'script-started backend process stopped' $false
}

function Get-ApiIdentity {
    param([Parameter(Mandatory = $true)][string]$Role)

    $email = "$($script:EmailPrefix).$($Role.ToLowerInvariant())@example.invalid"
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }
    $password = 'Ss!' + [BitConverter]::ToString($bytes).Replace('-', '') + 'z9'
    $script:IdentityEmails[$Role] = $email
    $script:IdentityNames[$Role] = "Step11 $Role"

    $registered = Get-HttpResult -Method POST -Path '/api/auth/register' -Body @{
        name = $script:IdentityNames[$Role]
        email = $email
        password = $password
    }
    Assert-HttpStatus $registered 201 "register disposable user $Role"
    $user = $registered.Body | ConvertFrom-Json
    if (-not $user.id -or $user.email -ne $email) {
        throw "Registration response for user $Role was incomplete."
    }
    $script:Users[$Role] = @{ Id = [long]$user.id; Email = $email }

    $login = Get-HttpResult -Method POST -Path '/api/auth/login' -Body @{
        email = $email
        password = $password
    }
    Assert-HttpStatus $login 200 "authenticate disposable user $Role"
    $token = ($login.Body | ConvertFrom-Json).accessToken
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "Login response for user $Role did not contain an access token."
    }
    return $token
}

function Get-DisposableExpenseCount {
    if (-not $script:GroupId) {
        return [long]0
    }
    return [long](Invoke-MySqlScalar -Sql @"
SELECT COUNT(*) FROM expenses WHERE group_id = $($script:GroupId)
"@)
}

function Confirm-ExpenseRecord {
    $rows = @(Invoke-MySql -Sql @"
SELECT id, group_id, description, CAST(amount AS CHAR), currency_code,
       paid_by_user_id, created_by_user_id
FROM expenses
WHERE id = $($script:ExpenseId)
"@)
    if ($rows.Count -ne 1) {
        Stop-WithCheck 'exactly one created expense row exists in MySQL'
    }
    $columns = $rows[0] -split "`t", 7
    $expected = @(
        [string]$script:ExpenseId,
        [string]$script:GroupId,
        'Dinner',
        '900.00',
        'INR',
        [string]$script:Users['B'].Id,
        [string]$script:Users['A'].Id
    )
    for ($index = 0; $index -lt $expected.Count; $index++) {
        if ($columns[$index] -ne $expected[$index]) {
            Stop-WithCheck 'persisted MySQL expense fields exactly match the expected values'
        }
    }

    $splits = @(Invoke-MySql -Sql @"
SELECT user_id, CAST(amount AS CHAR)
FROM expense_splits
WHERE expense_id = $($script:ExpenseId)
ORDER BY user_id
"@)
    $expectedSplits = @(
        "$($script:Users['A'].Id)`t400.00",
        "$($script:Users['B'].Id)`t300.00",
        "$($script:Users['C'].Id)`t200.00"
    )
    $splits = @($splits | Sort-Object)
    $expectedSplits = @($expectedSplits | Sort-Object)
    $splitDifferences = @(Compare-Object $splits $expectedSplits)
    if ($splits.Count -ne 3 -or $splitDifferences.Count -ne 0) {
        Stop-WithCheck 'exactly three exact split rows exist for the created expense'
    }
    Write-Check 'MySQL expense row has the expected group, amount, INR, payer and creator' $true
    Write-Check 'MySQL contains exactly three exact split rows' $true
}

function Confirm-MigrationAndSchema {
    $rows = @(Invoke-MySql -Sql @"
SELECT version, success FROM flyway_schema_history
WHERE type = 'SQL' ORDER BY installed_rank
"@)
    $versions = @($rows | ForEach-Object { ($_ -split "`t", 2)[0] })
    $success = @($rows | ForEach-Object { ($_ -split "`t", 2)[1] })
    if ($versions -notcontains '1' -or $versions -notcontains '2' -or
        $versions.Count -ne 2 -or $success -contains '0') {
        Stop-WithCheck 'Flyway has exactly successful V1 and V2 migrations'
    }
    if ($script:V2WasApplied) {
        Write-Check 'Flyway V2 was already applied and remains successful' $true
    }
    else {
        Write-Check 'Flyway applied the pending V2 migration successfully' $true
    }
    Confirm-ExpenseSchema
}

function Remove-DisposableData {
    if (-not $script:ConnectionVerified -or $script:IdentityEmails.Count -eq 0) {
        return
    }

    try {
        foreach ($role in @($script:IdentityEmails.Keys)) {
            if (-not $script:Users.ContainsKey($role)) {
                $emailLiteral = $script:IdentityEmails[$role].Replace("'", "''")
                $nameLiteral = $script:IdentityNames[$role].Replace("'", "''")
                $identityRows = @(Invoke-MySql -Sql @"
SELECT id FROM users
WHERE email = '$emailLiteral' AND name = '$nameLiteral'
"@)
                if ($identityRows.Count -gt 1) {
                    throw "Disposable identity lookup was not unique for role $role."
                }
                if ($identityRows.Count -eq 1) {
                    $script:Users[$role] = @{
                        Id = [long]$identityRows[0]
                        Email = $script:IdentityEmails[$role]
                    }
                }
            }
        }

        if (-not $script:GroupId -and $script:Users.ContainsKey('A')) {
            $groupNameLiteral = $script:GroupName.Replace("'", "''")
            $groups = @(Invoke-MySql -Sql @"
SELECT id FROM expense_groups
WHERE name = '$groupNameLiteral' AND created_by = $($script:Users['A'].Id)
"@)
            if ($groups.Count -gt 1) {
                throw 'Disposable group lookup was not unique; cleanup stopped.'
            }
            if ($groups.Count -eq 1) {
                $script:GroupId = [long]$groups[0]
                if ($script:GroupMemberRoles.Count -eq 0) {
                    $script:GroupMemberRoles = @('A')
                }
            }
        }
        if (-not $script:ExpenseId -and $script:GroupId -and $script:Users.ContainsKey('A')) {
            $candidateExpenses = @(Invoke-MySql -Sql @"
SELECT id FROM expenses
WHERE group_id = $($script:GroupId)
  AND description = 'Dinner'
  AND amount = 900.00
  AND currency_code = 'INR'
  AND paid_by_user_id = $($script:Users['B'].Id)
  AND created_by_user_id = $($script:Users['A'].Id)
"@)
            if ($candidateExpenses.Count -gt 1) {
                throw 'Disposable expense lookup was not unique; cleanup stopped.'
            }
            if ($candidateExpenses.Count -eq 1) {
                $script:ExpenseId = [long]$candidateExpenses[0]
            }
        }

        if ($script:ExpenseId) {
            $expense = @(Invoke-MySql -Sql @"
SELECT group_id, description, CAST(amount AS CHAR), currency_code,
       paid_by_user_id, created_by_user_id
FROM expenses
WHERE id = $($script:ExpenseId)
"@)
            if ($expense.Count -gt 0) {
                $parts = $expense[0] -split "`t", 6
                if ($expense.Count -ne 1 -or
                    $parts[0] -ne [string]$script:GroupId -or
                    $parts[1] -ne 'Dinner' -or
                    $parts[2] -ne '900.00' -or
                    $parts[3] -ne 'INR' -or
                    $parts[4] -ne [string]$script:Users['B'].Id -or
                    $parts[5] -ne [string]$script:Users['A'].Id) {
                    throw 'Expense identity or ownership did not match; cleanup stopped.'
                }
                $splitRows = @(Invoke-MySql -Sql @"
SELECT user_id, CAST(amount AS CHAR)
FROM expense_splits
WHERE expense_id = $($script:ExpenseId)
ORDER BY user_id
"@)
                $expectedSplits = @(
                    "$($script:Users['A'].Id)`t400.00",
                    "$($script:Users['B'].Id)`t300.00",
                    "$($script:Users['C'].Id)`t200.00"
                )
                $splitRows = @($splitRows | Sort-Object)
                $expectedSplits = @($expectedSplits | Sort-Object)
                $splitDifferences = @(Compare-Object $splitRows $expectedSplits)
                if ($splitRows.Count -ne 3 -or $splitDifferences.Count -ne 0) {
                    throw 'Expense split identities or amounts did not match the tracked participants.'
                }
                [void](Invoke-MySql -Sql "DELETE FROM expense_splits WHERE expense_id = $($script:ExpenseId)")
                [void](Invoke-MySql -Sql "DELETE FROM expenses WHERE id = $($script:ExpenseId) AND group_id = $($script:GroupId) AND description = 'Dinner' AND amount = 900.00 AND currency_code = 'INR' AND paid_by_user_id = $($script:Users['B'].Id) AND created_by_user_id = $($script:Users['A'].Id)")
                if ((Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM expenses WHERE id = $($script:ExpenseId)") -ne '0') {
                    throw 'Tracked expense remained after exact-ID cleanup.'
                }
            }
        }

        if ($script:GroupId) {
            $groupRows = @(Invoke-MySql -Sql @"
SELECT created_by, name FROM expense_groups WHERE id = $($script:GroupId)
"@)
            if ($groupRows.Count -gt 0) {
                $groupParts = $groupRows[0] -split "`t", 2
                if ($groupRows.Count -ne 1 -or
                    $groupParts[0] -ne [string]$script:Users['A'].Id -or
                    $groupParts[1] -ne $script:GroupName) {
                    throw 'Group identity or ownership did not match; cleanup stopped.'
                }
                if ((Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM expenses WHERE group_id = $($script:GroupId)") -ne '0') {
                    throw 'Unexpected expense dependencies remain for the disposable group.'
                }
                $memberRows = @(Invoke-MySql -Sql @"
SELECT user_id FROM group_members WHERE group_id = $($script:GroupId) ORDER BY user_id
"@)
                $expectedUserIds = @(
                    $script:GroupMemberRoles |
                        ForEach-Object { [string]$script:Users[$_].Id }
                )
                $expectedUserIds = @($expectedUserIds | Sort-Object)
                $membershipDifferences = @(Compare-Object @($memberRows | Sort-Object) $expectedUserIds)
                if ($memberRows.Count -ne $expectedUserIds.Count -or
                    $membershipDifferences.Count -ne 0) {
                    throw 'Group membership identities did not match; cleanup stopped.'
                }
                foreach ($user in $script:Users.Values) {
                    $emailLiteral = $user.Email.Replace("'", "''")
                    $identity = @(Invoke-MySql -Sql @"
SELECT id FROM users WHERE id = $($user.Id) AND email = '$emailLiteral'
"@)
                    if ($identity.Count -ne 1 -or $identity[0] -ne [string]$user.Id) {
                        throw 'Disposable user identity check failed; cleanup stopped.'
                    }
                }
                [void](Invoke-MySql -Sql "DELETE FROM group_members WHERE group_id = $($script:GroupId) AND user_id IN ($($expectedUserIds -join ','))")
                [void](Invoke-MySql -Sql "DELETE FROM expense_groups WHERE id = $($script:GroupId) AND created_by = $($script:Users['A'].Id) AND name = '$($script:GroupName)'")
                if ((Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM expense_groups WHERE id = $($script:GroupId)") -ne '0') {
                    throw 'Tracked group remained after exact-ID cleanup.'
                }
            }
        }

        foreach ($user in $script:Users.Values) {
            $emailLiteral = $user.Email.Replace("'", "''")
            $remainingMemberships = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM group_members WHERE user_id = $($user.Id)"
            $remainingGroups = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM expense_groups WHERE created_by = $($user.Id)"
            $remainingExpenses = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM expenses WHERE paid_by_user_id = $($user.Id) OR created_by_user_id = $($user.Id)"
            $remainingSplits = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM expense_splits WHERE user_id = $($user.Id)"
            if ($remainingMemberships -ne '0' -or $remainingGroups -ne '0' -or
                $remainingExpenses -ne '0' -or $remainingSplits -ne '0') {
                throw "Disposable user $($user.Id) still has dependent data; user was not deleted."
            }
            [void](Invoke-MySql -Sql "DELETE FROM users WHERE id = $($user.Id) AND email = '$emailLiteral'")
            if ((Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM users WHERE id = $($user.Id)") -ne '0') {
                throw "Disposable user $($user.Id) remained after exact-ID cleanup."
            }
        }
        Write-Check 'tracked users, group, expense and splits were safely cleaned up' $true
    }
    catch {
        $script:CleanupSafe = $false
        Write-Check -Name 'safe cleanup of only tracked disposable records' -Passed $false `
            -Detail 'identity/dependency check failed; remaining IDs are reported below'
        Write-Host "Remaining tracked user IDs: $(@($script:Users.Values | ForEach-Object { $_.Id }) -join ', ')"
        if ($script:IdentityEmails.Count -gt $script:Users.Count) {
            Write-Host 'One or more registration responses did not yield an ID; unique email lookup is required before any manual cleanup.'
        }
        if ($script:GroupId) {
            Write-Host "Tracked group ID: $($script:GroupId)"
            try {
                $remainingExpenses = @(Invoke-MySql -Sql "SELECT id FROM expenses WHERE group_id = $($script:GroupId) ORDER BY id")
                $remainingMembers = @(Invoke-MySql -Sql "SELECT user_id FROM group_members WHERE group_id = $($script:GroupId) ORDER BY user_id")
                Write-Host "Remaining expense IDs for that group: $($remainingExpenses -join ', ')"
                Write-Host "Remaining member user IDs for that group: $($remainingMembers -join ', ')"
            }
            catch {
                Write-Host 'Remaining group dependencies could not be queried; do not perform manual broad deletion.'
            }
        }
        if ($script:ExpenseId) { Write-Host "Tracked expense ID: $($script:ExpenseId)" }
    }
}

function Restore-ProcessEnvironment {
    foreach ($key in $script:OriginalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $script:OriginalEnvironment[$key], 'Process')
    }
}

function Stop-OwnedMysqlIfPossible {
    if (-not $script:OwnedMysqlRootPid) {
        return
    }
    $listenerProcesses = @(Get-ListenerProcesses)
    if ($listenerProcesses.Count -eq 0 -and
        -not (Get-CimInstance Win32_Process -Filter "ProcessId=$($script:OwnedMysqlRootPid)" `
            -ErrorAction SilentlyContinue)) {
        return
    }
    if (-not $script:ConnectionVerified) {
        Write-Host 'WARN: MySQL started by this script remains running because authenticated graceful shutdown could not be verified.'
        return
    }
    if (-not (Test-Path -LiteralPath $script:MysqlAdminExe -PathType Leaf)) {
        Write-Host 'INFO: MySQL started by this script remains running; mysqladmin is unavailable for graceful shutdown.'
        return
    }
    if ($listenerProcesses.Count -ne 1 -or
        $listenerProcesses[0].ProcessId -ne $script:OwnedMysqlListenerPid -or
        -not (Test-ExpectedMysqlProcess $listenerProcesses[0])) {
        Write-Host 'WARN: MySQL listener ownership changed; it was not stopped.'
        return
    }

    $shutdownOutput = & $script:MysqlAdminExe `
        --no-defaults `
        --protocol=TCP `
        "--host=$($script:DbSettings.Host)" `
        "--port=$($script:DbSettings.Port)" `
        "--user=$($script:DbSettings.Username)" `
        shutdown 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'INFO: MySQL started by this script remains running; graceful shutdown was not authorized for the app account.'
        return
    }

    $deadline = (Get-Date).AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 500
        $remaining = @(Get-CimInstance Win32_Process -Filter "ProcessId=$($script:OwnedMysqlListenerPid)" `
            -ErrorAction SilentlyContinue)
        if ($remaining.Count -eq 0) {
            Write-Host 'PASS: script-started MySQL shut down gracefully'
            return
        }
    } while ((Get-Date) -lt $deadline)
    Write-Host 'WARN: MySQL accepted graceful shutdown but the process has not exited yet.'
}

try {
    Write-Host 'SplitSmart Step 11 local MySQL verification'
    $script:CurrentStage = 'temporary credential-file check'
    Remove-LegacyCredentialFiles

    $script:CurrentStage = 'Java and MySQL prerequisites'
    if (-not (Test-Path -LiteralPath $script:JavaExe -PathType Leaf)) {
        Stop-WithCheck 'portable Java 21 is present'
    }
    $javaVersionOutput = & $script:JavaExe --version
    $javaVersionExitCode = $LASTEXITCODE
    $javaVersion = $javaVersionOutput -join "`n"
    if ($javaVersionExitCode -ne 0 -or $javaVersion -notmatch '^openjdk 21\.') {
        Stop-WithCheck 'portable Java installation is Java 21'
    }
    Write-Check 'portable Java 21 is available' $true

    foreach ($path in @($script:MysqlExe, $script:MysqldExe, $script:MysqlConfig, $script:DataDirectory)) {
        if (-not (Test-Path -LiteralPath $path)) {
            Stop-WithCheck "existing MySQL resource is available: $([IO.Path]::GetFileName($path))"
        }
    }
    Write-Check 'portable MySQL binaries and existing data/configuration are present' $true
    $mysqlVersionOutput = & $script:MysqldExe --version
    $mysqlVersionExitCode = $LASTEXITCODE
    $mysqlVersion = $mysqlVersionOutput -join "`n"
    if ($mysqlVersionExitCode -ne 0 -or $mysqlVersion -notmatch 'Ver 8\.4\.13(?:\s|$)') {
        Stop-WithCheck 'portable MySQL server binary is version 8.4.13'
    }
    Write-Check 'portable MySQL server binary is version 8.4.13' $true

    $script:CurrentStage = 'local settings and MySQL identity'
    Read-LocalSettings
    Start-ExistingMysqlIfNeeded
    Confirm-DatabaseIdentity
    $script:CurrentStage = 'pre-startup Flyway history validation'
    Confirm-MigrationHistoryBeforeStartup

    $script:CurrentStage = 'packaged backend startup'
    if (-not (Test-Path -LiteralPath $script:JarPath -PathType Leaf)) {
        Stop-WithCheck 'existing packaged backend JAR is present'
    }
    Start-Backend
    $script:CurrentStage = 'Flyway V2 and schema constraints'
    Confirm-MigrationAndSchema

    $migrationBeforeRestart = Invoke-MySqlScalar -Sql @"
SELECT CONCAT(installed_rank, CHAR(9), installed_on, CHAR(9), checksum)
FROM flyway_schema_history
WHERE version = '2' AND success = 1
"@

    $script:CurrentStage = 'disposable user registration and authentication'
    foreach ($role in @('A', 'B', 'C', 'D')) {
        $script:Tokens[$role] = Get-ApiIdentity -Role $role
    }

    $script:CurrentStage = 'group and membership setup'
    $createdGroup = Get-HttpResult -Method POST -Path '/api/groups' `
        -Token $script:Tokens['A'] -Body @{ name = $script:GroupName }
    Assert-HttpStatus $createdGroup 201 'create disposable group'
    $script:GroupId = [long](($createdGroup.Body | ConvertFrom-Json).id)
    if (-not $script:GroupId) { throw 'Group API did not return a generated group ID.' }
    $script:GroupMemberRoles = @('A')

    foreach ($role in @('B', 'C')) {
        $added = Get-HttpResult -Method POST -Path "/api/groups/$($script:GroupId)/members" `
            -Token $script:Tokens['A'] -Body @{ email = $script:Users[$role].Email }
        Assert-HttpStatus $added 201 "add disposable user $role to group"
        $script:GroupMemberRoles += $role
        if ([long](($added.Body | ConvertFrom-Json).userId) -ne $script:Users[$role].Id) {
            throw "Group API returned an unexpected member ID for user $role."
        }
    }

    $script:CurrentStage = 'expense creation and financial persistence'
    $expenseBody = @{
        description = 'Dinner'
        amount = [decimal]900.00
        paidByUserId = $script:Users['B'].Id
        splits = @(
            @{ userId = $script:Users['A'].Id; amount = [decimal]400.00 }
            @{ userId = $script:Users['B'].Id; amount = [decimal]300.00 }
            @{ userId = $script:Users['C'].Id; amount = [decimal]200.00 }
        )
    }
    $createdExpense = Get-HttpResult -Method POST -Path "/api/groups/$($script:GroupId)/expenses" `
        -Token $script:Tokens['A'] -Body $expenseBody
    Assert-HttpStatus $createdExpense 201 'create Dinner expense'
    $expense = $createdExpense.Body | ConvertFrom-Json
    $script:ExpenseId = [long]$expense.id
    if (-not $script:ExpenseId -or
        [long]$expense.groupId -ne $script:GroupId -or
        [decimal]$expense.amount -ne [decimal]900.00 -or
        $expense.currencyCode -ne 'INR' -or
        [long]$expense.paidByUserId -ne $script:Users['B'].Id -or
        [long]$expense.createdByUserId -ne $script:Users['A'].Id) {
        throw 'Expense creation response did not match the expected values.'
    }
    $expenseSplits = @($expense.splits)
    if ($expenseSplits.Count -ne 3) {
        throw 'Expense creation response did not contain exactly three splits.'
    }
    $responseSplits = @($expenseSplits | ForEach-Object {
        "$($_.userId)`t$(([decimal]$_.amount).ToString('0.00', [Globalization.CultureInfo]::InvariantCulture))"
    })
    $expectedResponseSplits = @(
        "$($script:Users['A'].Id)`t400.00",
        "$($script:Users['B'].Id)`t300.00",
        "$($script:Users['C'].Id)`t200.00"
    )
    $responseSplits = @($responseSplits | Sort-Object)
    $expectedResponseSplits = @($expectedResponseSplits | Sort-Object)
    $responseSplitDifferences = @(Compare-Object $responseSplits $expectedResponseSplits)
    if ($responseSplitDifferences.Count -ne 0) {
        throw 'Expense response splits did not match the expected exact amounts.'
    }
    Write-Check 'API response records INR 900.00 with User B payer and User A creator' $true
    Write-Check 'API response contains the expected three split participants and amounts' $true

    Confirm-ExpenseRecord
    $script:CurrentStage = 'expense listing and authorization'
    $listed = Get-HttpResult -Method GET -Path "/api/groups/$($script:GroupId)/expenses" `
        -Token $script:Tokens['A']
    Assert-HttpStatus $listed 200 'list group expenses'
    $listedExpenses = @($listed.Body | ConvertFrom-Json)
    if ($listedExpenses.Count -ne 1 -or [long]$listedExpenses[0].id -ne $script:ExpenseId) {
        throw 'Group expense listing did not return exactly the created expense.'
    }
    Write-Check 'group expense listing returns exactly the created expense' $true

    $unrelatedList = Get-HttpResult -Method GET -Path "/api/groups/$($script:GroupId)/expenses" `
        -Token $script:Tokens['D']
    Assert-HttpStatus $unrelatedList 404 'unrelated user cannot list expenses'
    $unrelatedCreate = Get-HttpResult -Method POST -Path "/api/groups/$($script:GroupId)/expenses" `
        -Token $script:Tokens['D'] -Body $expenseBody
    Assert-HttpStatus $unrelatedCreate 404 'unrelated user cannot create expenses'

    $missingAuthList = Get-HttpResult -Method GET -Path "/api/groups/$($script:GroupId)/expenses"
    Assert-HttpStatus $missingAuthList 401 'missing authentication cannot list expenses'
    $missingAuthCreate = Get-HttpResult -Method POST -Path "/api/groups/$($script:GroupId)/expenses" `
        -Body $expenseBody
    Assert-HttpStatus $missingAuthCreate 401 'missing authentication cannot create expenses'

    $script:CurrentStage = 'invalid request and no-partial-write verification'
    $beforeInvalid = Get-DisposableExpenseCount
    $invalidBody = @{
        description = 'Invalid verification total'
        amount = [decimal]900.00
        paidByUserId = $script:Users['B'].Id
        splits = @(
            @{ userId = $script:Users['A'].Id; amount = [decimal]400.00 }
            @{ userId = $script:Users['B'].Id; amount = [decimal]300.00 }
            @{ userId = $script:Users['C'].Id; amount = [decimal]199.99 }
        )
    }
    $invalid = Get-HttpResult -Method POST -Path "/api/groups/$($script:GroupId)/expenses" `
        -Token $script:Tokens['A'] -Body $invalidBody
    Assert-HttpStatus $invalid 400 'incorrect split total is rejected'
    if ((Get-DisposableExpenseCount) -ne $beforeInvalid -or
        (Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM expense_splits WHERE expense_id IN (SELECT id FROM expenses WHERE group_id = $($script:GroupId))") -ne '3') {
        Stop-WithCheck 'invalid expense creation leaves no partial database rows'
    }
    Write-Check 'invalid expense creation left no extra database rows' $true

    $script:CurrentStage = 'health and backend restart verification'
    $health = Get-HttpResult -Method GET -Path '/api/health'
    Assert-HttpStatus $health 200 'health endpoint remains available'

    Stop-Backend
    Start-Backend
    $migrationAfterRestart = Invoke-MySqlScalar -Sql @"
SELECT CONCAT(installed_rank, CHAR(9), installed_on, CHAR(9), checksum)
FROM flyway_schema_history
WHERE version = '2' AND success = 1
"@
    if ($migrationAfterRestart -ne $migrationBeforeRestart) {
        Stop-WithCheck 'restarting the backend did not reapply Flyway V2'
    }
    Write-Check 'backend restart leaves V2 history unchanged' $true
    $healthAfterRestart = Get-HttpResult -Method GET -Path '/api/health'
    Assert-HttpStatus $healthAfterRestart 200 'health endpoint responds after restart'
}
catch {
    if ($script:Failures -eq 0) {
        $script:Failures++
    }

    $exceptionType = $_.Exception.GetType().FullName
    $safeMessage = [string]$_.Exception.Message
    foreach ($secretName in @('DB_PASSWORD', 'JWT_SECRET', 'MYSQL_PWD')) {
        $secretValue = [Environment]::GetEnvironmentVariable($secretName, 'Process')
        if (-not [string]::IsNullOrEmpty($secretValue)) {
            $safeMessage = $safeMessage.Replace($secretValue, '[redacted]')
        }
    }
    $safeMessage = [regex]::Replace(
        $safeMessage,
        '(?i)\bBearer\s+[A-Za-z0-9._~+/-]+=*',
        'Bearer [redacted]')
    $safeMessage = [regex]::Replace(
        $safeMessage,
        '\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b',
        '[redacted JWT]')
    $safeMessage = [regex]::Replace(
        $safeMessage,
        '(?i)\b(password|secret|accessToken)\s*[:=]\s*[^;\s,]+',
        '$1=[redacted]')
    if ($safeMessage -match '(?i)\b(SELECT|INSERT|UPDATE|DELETE|ALTER|DROP|TRUNCATE)\b') {
        $safeMessage = 'Database operation failed; query details suppressed.'
    }
    if ($safeMessage.Length -gt 240) {
        $safeMessage = $safeMessage.Substring(0, 240) + '...'
    }

    $lineNumber = $_.InvocationInfo.ScriptLineNumber
    if (-not $lineNumber -and $_.ScriptStackTrace -match ':\s*line\s+(\d+)') {
        $lineNumber = [int]$matches[1]
    }
    $functionName = $_.InvocationInfo.MyCommand.Name
    if ([string]::IsNullOrWhiteSpace($functionName) -and
        $_.ScriptStackTrace -match 'at\s+([^\s,]+)') {
        $functionName = $matches[1]
    }
    if ([string]::IsNullOrWhiteSpace($functionName)) {
        $functionName = '<script>'
    }
    Write-Host "FAIL: stage=$($script:CurrentStage); type=$exceptionType; message=$safeMessage; line=$lineNumber; function=$functionName" -ForegroundColor Red
}
finally {
    Stop-Backend
    if ($script:ConnectionVerified) {
        Remove-DisposableData
    }
    if ($script:OwnedMysqlRootPid) {
        Stop-OwnedMysqlIfPossible
    }
    Restore-ProcessEnvironment

    foreach ($path in @($script:AppStdout, $script:AppStderr)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue
        }
    }
    $mysqlLogs = @(
        (Join-Path $env:TEMP "splitsmart-step11-mysql-$($script:VerificationId).out.log"),
        (Join-Path $env:TEMP "splitsmart-step11-mysql-$($script:VerificationId).err.log")
    )
    $mysqlStillRunning = $false
    if ($script:OwnedMysqlListenerPid) {
        $mysqlStillRunning = [bool](Get-CimInstance Win32_Process `
            -Filter "ProcessId=$($script:OwnedMysqlListenerPid)" -ErrorAction SilentlyContinue)
    }
    if ($mysqlStillRunning) {
        Write-Host "INFO: MySQL diagnostic logs were retained outside the repository: $($mysqlLogs -join ', ')"
    }
    else {
        foreach ($path in $mysqlLogs) {
            if (Test-Path -LiteralPath $path) {
                Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue
            }
        }
    }
}

Write-Host "Summary: $($script:Passes) passed, $($script:Failures) failed."
if (-not $script:CleanupSafe) {
    Write-Host 'FAIL: cleanup did not complete safely; inspect only the reported tracked IDs.'
}
if ($script:Failures -gt 0 -or -not $script:CleanupSafe) {
    exit 1
}
exit 0
