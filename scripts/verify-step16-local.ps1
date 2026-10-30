[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:BackendRoot = Join-Path $script:RepoRoot 'backend'
$script:BaseUrl = 'http://127.0.0.1:8081'
$script:MysqlExe = Join-Path $HOME 'mysql\mysql-8.4.13\bin\mysql.exe'
$script:MysqlConfig = Join-Path $HOME 'mysql\splitsmart\my.ini'
$script:MysqlProcessPath = Join-Path $HOME 'mysql\mysql-8.4.13\bin\mysqld.exe'
$script:DataDirectory = Join-Path $HOME 'mysql\splitsmart\data'
$script:EnvPath = Join-Path $script:BackendRoot '.env.local'
$script:RunId = [Guid]::NewGuid().ToString('N')
$script:GroupName = "Step16-$($script:RunId)"
$script:Password = [Guid]::NewGuid().ToString('N') + 'Aa1!'
$script:Identities = @{}
$script:Users = @{}
$script:Tokens = @{}
$script:GroupId = $null
$script:ExpenseId = $null
$script:SettlementRequestId = [Guid]::NewGuid().ToString()
$script:Passes = 0
$script:Failures = 0
$script:MainFailure = $null
$script:CleanupFailure = $null
$script:OriginalMysqlPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
$script:Settings = @{}
$script:InitialHistory = $null

function Write-Pass {
	param([Parameter(Mandatory = $true)][string]$Name)
	$script:Passes++
	Write-Host "PASS: $Name"
}

function Assert-True {
	param(
		[Parameter(Mandatory = $true)][bool]$Condition,
		[Parameter(Mandatory = $true)][string]$Name
	)
	if (-not $Condition) {
		$script:Failures++
		throw "Verification failed: $Name"
	}
	Write-Pass $Name
}

function Invoke-LocalApi {
	param(
		[Parameter(Mandatory = $true)]
		[ValidateSet('GET', 'POST', 'PUT', 'DELETE')]
		[string]$Method,
		[Parameter(Mandatory = $true)][string]$Path,
		[string]$Token,
		[object]$Body
	)

	$headers = @{}
	if ($Token) {
		$headers['Authorization'] = "Bearer $Token"
	}
	$request = @{
		Uri = "$($script:BaseUrl)$Path"
		Method = $Method
		Headers = $headers
		UseBasicParsing = $true
		TimeoutSec = 20
		ErrorAction = 'Stop'
	}
	if ($PSBoundParameters.ContainsKey('Body')) {
		$request['ContentType'] = 'application/json'
		$request['Body'] = ConvertTo-Json -InputObject $Body -Depth 8 -Compress
	}

	$content = ''
	try {
		$response = Invoke-WebRequest @request
		$status = [int]$response.StatusCode
		$content = [string]$response.Content
	}
	catch {
		$httpResponse = $_.Exception.Response
		if ($null -eq $httpResponse) {
			throw 'Local API request failed without an HTTP response.'
		}
		$status = [int]$httpResponse.StatusCode
		if ($httpResponse -is [System.Net.HttpWebResponse]) {
			$reader = New-Object IO.StreamReader($httpResponse.GetResponseStream())
			try {
				$content = $reader.ReadToEnd()
			}
			finally {
				$reader.Dispose()
			}
		}
	}

	$json = $null
	if (-not [string]::IsNullOrWhiteSpace($content)) {
		try {
			$json = ConvertFrom-Json -InputObject $content
		}
		catch {
			throw 'Local API returned an invalid JSON response.'
		}
	}
	return @{ Status = $status; Json = $json }
}

function Invoke-MySql {
	param([Parameter(Mandatory = $true)][string]$Sql)

	$output = & $script:MysqlExe `
		--no-defaults `
		--protocol=TCP `
		--host=127.0.0.1 `
		--port=3306 `
		--user=splitsmart_app `
		--database=splitsmart_dev `
		--batch `
		--raw `
		--skip-column-names `
		"--execute=$Sql" 2>$null
	if ($LASTEXITCODE -ne 0) {
		throw 'A local MySQL verification query failed; client output was withheld.'
	}
	return @($output | ForEach-Object { [string]$_ })
}

function Invoke-MySqlScalar {
	param([Parameter(Mandatory = $true)][string]$Sql)
	$rows = @(Invoke-MySql -Sql $Sql)
	if ($rows.Count -ne 1) {
		throw 'A local MySQL verification query did not return exactly one row.'
	}
	return [string]$rows[0]
}

function Assert-HttpStatus {
	param(
		[Parameter(Mandatory = $true)][hashtable]$Response,
		[Parameter(Mandatory = $true)][int]$Expected,
		[Parameter(Mandatory = $true)][string]$Name
	)
	Assert-True ($Response.Status -eq $Expected) $Name
}

function Read-LocalSettings {
	if (-not (Test-Path -LiteralPath $script:EnvPath -PathType Leaf)) {
		throw 'Ignored backend/.env.local is missing.'
	}
	foreach ($line in Get-Content -LiteralPath $script:EnvPath) {
		if ($line -match '^\s*([^#=]+)=(.*)$') {
			$script:Settings[$matches[1].Trim()] = $matches[2]
		}
	}
	foreach ($key in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET')) {
		if ([string]::IsNullOrWhiteSpace($script:Settings[$key])) {
			throw 'A required local application setting is missing.'
		}
	}
	if ($script:Settings['DB_URL'] -notmatch '^jdbc:mysql://(localhost|127\.0\.0\.1)(:3306)?/splitsmart_dev(?:\?|$)' -or
		$script:Settings['DB_USERNAME'] -ne 'splitsmart_app') {
		throw 'Local application settings do not target the expected SplitSmart schema.'
	}
	[Environment]::SetEnvironmentVariable(
		'MYSQL_PWD',
		$script:Settings['DB_PASSWORD'],
		'Process')
}

function Confirm-LocalInstances {
	$mysqlListeners = @(
		Get-NetTCPConnection -LocalPort 3306 -State Listen `
			-ErrorAction SilentlyContinue
	)
	Assert-True ($mysqlListeners.Count -eq 1) 'one local MySQL listener is present'
	$mysqlProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($mysqlListeners[0].OwningProcess)"
	$expectedConfig = [IO.Path]::GetFullPath($script:MysqlConfig)
	$configMatches = $mysqlProcess.CommandLine.IndexOf(
		$expectedConfig,
		[StringComparison]::OrdinalIgnoreCase) -ge 0
	Assert-True (
		[IO.Path]::GetFullPath($mysqlProcess.ExecutablePath) -eq [IO.Path]::GetFullPath($script:MysqlProcessPath) -and
		$configMatches) 'MySQL listener is the configured portable SplitSmart instance'

	$backendListeners = @(
		Get-NetTCPConnection -LocalPort 8081 -State Listen `
			-ErrorAction SilentlyContinue
	)
	Assert-True ($backendListeners.Count -eq 1) 'one local backend listener is present'
	$backendProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $($backendListeners[0].OwningProcess)"
	Assert-True (
		$backendProcess.ExecutablePath -like '*\java.exe' -and
		$backendProcess.CommandLine -like '*splitsmart-backend-0.0.1-SNAPSHOT.jar*' -and
		$backendProcess.CommandLine -like '*--server.port=8081*') 'backend listener is the packaged local SplitSmart JAR'

	$database = Invoke-MySqlScalar -Sql @"
SELECT CONCAT(VERSION(), '|', DATABASE(), '|', @@port, '|', @@bind_address, '|', @@datadir)
"@
	$parts = $database -split '\|', 5
	$expectedData = [IO.Path]::GetFullPath($script:DataDirectory).TrimEnd('\') + '\'
	$actualData = [IO.Path]::GetFullPath($parts[4].Replace('/', '\')).TrimEnd('\') + '\'
	Assert-True (
		$parts.Count -eq 5 -and $parts[0] -match '^8\.4\.13(?:-|$)' -and
		$parts[1] -eq 'splitsmart_dev' -and $parts[2] -eq '3306' -and
		$parts[3] -in @('127.0.0.1', 'localhost') -and
		[string]::Equals($expectedData, $actualData, [StringComparison]::OrdinalIgnoreCase)) `
		'MySQL 8.4.13 uses splitsmart_dev, loopback and the existing data directory'
}

function Get-MigrationHistory {
	return @(Invoke-MySql -Sql @"
SELECT CONCAT(installed_rank, '|', COALESCE(version, ''), '|',
              COALESCE(checksum, ''), '|', success, '|', type)
FROM flyway_schema_history
ORDER BY installed_rank
"@)
}

function Confirm-MigrationHistory {
	$rows = @(Get-MigrationHistory)
	Assert-True ($rows.Count -eq 3) 'Flyway has exactly three migration entries'
	$expectedVersions = @('1', '2', '3')
	for ($index = 0; $index -lt $expectedVersions.Count; $index++) {
		$parts = $rows[$index] -split '\|', 5
		Assert-True (
			$parts.Count -eq 5 -and $parts[0] -eq [string]($index + 1) -and
			$parts[1] -eq $expectedVersions[$index] -and
			-not [string]::IsNullOrWhiteSpace($parts[2]) -and
			$parts[3] -eq '1' -and $parts[4] -eq 'SQL') `
			"Flyway migration V$($index + 1) is successful and checksum-recorded"
	}
	if ($null -eq $script:InitialHistory) {
		$script:InitialHistory = @($rows)
	}
	else {
		$unchanged = $rows.Count -eq $script:InitialHistory.Count
		if ($unchanged) {
			for ($index = 0; $index -lt $rows.Count; $index++) {
				if ($rows[$index] -ne $script:InitialHistory[$index]) {
					$unchanged = $false
					break
				}
			}
		}
		Assert-True $unchanged 'Flyway migration history, including checksums, is unchanged'
	}
}

function New-DisposableUser {
	param([Parameter(Mandatory = $true)][string]$Role)
	$email = "step16-$($script:RunId)-$Role@example.test"
	$name = "Step16-$($script:RunId)-$Role"
	$script:Identities[$Role] = @{ Email = $email; Name = $name; Id = $null }
	$registration = Invoke-LocalApi -Method POST -Path '/api/auth/register' -Body @{
		name = $name
		email = $email
		password = $script:Password
	}
	Assert-HttpStatus $registration 201 "disposable user $Role registered"
	$userId = [long]$registration.Json.id
	$script:Identities[$Role].Id = $userId
	$script:Users[$Role] = $userId

	$login = Invoke-LocalApi -Method POST -Path '/api/auth/login' -Body @{
		email = $email
		password = $script:Password
	}
	Assert-HttpStatus $login 200 "disposable user $Role authenticated"
	if ([string]::IsNullOrWhiteSpace($login.Json.accessToken)) {
		throw 'A disposable account did not receive an access token.'
	}
	$script:Tokens[$Role] = [string]$login.Json.accessToken
}

function Get-ExpenseCounts {
	param([Parameter(Mandatory = $true)][long]$GroupId)
	$row = Invoke-MySqlScalar -Sql @"
SELECT CONCAT(
 (SELECT COUNT(*) FROM expenses WHERE group_id = $GroupId), '|',
 (SELECT COUNT(*) FROM expense_splits s JOIN expenses e ON e.id = s.expense_id WHERE e.group_id = $GroupId), '|',
 (SELECT COUNT(*) FROM settlements WHERE group_id = $GroupId))
"@
	$counts = $row -split '\|', 3
	if ($counts.Count -ne 3) {
		throw 'The local record-count query returned an invalid result.'
	}
	return @{ Expenses = [int]$counts[0]; Splits = [int]$counts[1]; Settlements = [int]$counts[2] }
}

function Verify-InitialExpense {
	$row = Invoke-MySqlScalar -Sql @"
SELECT CONCAT(id, '|', group_id, '|', description, '|', CAST(amount AS CHAR), '|',
              paid_by_user_id, '|', created_by_user_id)
FROM expenses
WHERE id = $($script:ExpenseId)
  AND group_id = $($script:GroupId)
  AND description = 'Dinner'
  AND amount = 900.00
  AND paid_by_user_id = $($script:Users['B'])
  AND created_by_user_id = $($script:Users['A'])
"@
	$expense = $row -split '\|', 6
	Assert-True (
		$expense.Count -eq 6 -and $expense[0] -eq [string]$script:ExpenseId -and
		$expense[1] -eq [string]$script:GroupId -and $expense[3] -eq '900.00' -and
		$expense[4] -eq [string]$script:Users['B'] -and
		$expense[5] -eq [string]$script:Users['A']) 'MySQL expense has exact amount, payer, creator and group'

	$splits = @(Invoke-MySql -Sql @"
SELECT CONCAT(user_id, '|', CAST(amount AS CHAR))
FROM expense_splits
WHERE expense_id = $($script:ExpenseId)
ORDER BY user_id
"@)
	$expectedSplits = @(
		"$($script:Users['A'])|400.00",
		"$($script:Users['B'])|300.00",
		"$($script:Users['C'])|200.00"
	) | Sort-Object
	$actualSplits = @($splits | Sort-Object)
	Assert-True ($actualSplits.Count -eq 3) 'MySQL stores exactly three expense splits'
	for ($index = 0; $index -lt $expectedSplits.Count; $index++) {
		Assert-True ($actualSplits[$index] -eq $expectedSplits[$index]) "MySQL split $($index + 1) has the exact user and INR amount"
	}
}

function Invoke-LiveScenario {
	$health = Invoke-LocalApi -Method GET -Path '/api/health'
	Assert-HttpStatus $health 200 'health endpoint returns HTTP 200'
	Confirm-MigrationHistory

	foreach ($role in @('A', 'B', 'C', 'D')) {
		New-DisposableUser -Role $role
	}

	$createdGroup = Invoke-LocalApi -Method POST -Path '/api/groups' -Token $script:Tokens['A'] -Body @{
		name = $script:GroupName
	}
	Assert-HttpStatus $createdGroup 201 'User A created the disposable group'
	$script:GroupId = [long]$createdGroup.Json.id
	Assert-True ([long]$createdGroup.Json.createdByUserId -eq $script:Users['A']) 'User A owns the disposable group'

	foreach ($role in @('B', 'C')) {
		$added = Invoke-LocalApi -Method POST -Path "/api/groups/$($script:GroupId)/members" `
			-Token $script:Tokens['A'] -Body @{ email = $script:Identities[$role].Email }
		Assert-HttpStatus $added 201 "User $role was added to the group"
		Assert-True ([long]$added.Json.userId -eq $script:Users[$role]) "Group membership resolves to User $role"
	}

	$expenseBody = @{
		description = 'Dinner'
		amount = [decimal]900.00
		paidByUserId = $script:Users['B']
		splits = @(
			@{ userId = $script:Users['A']; amount = [decimal]400.00 },
			@{ userId = $script:Users['B']; amount = [decimal]300.00 },
			@{ userId = $script:Users['C']; amount = [decimal]200.00 }
		)
	}
	$expenseResponse = Invoke-LocalApi -Method POST `
		-Path "/api/groups/$($script:GroupId)/expenses" -Token $script:Tokens['A'] -Body $expenseBody
	Assert-HttpStatus $expenseResponse 201 'User A created the Dinner expense with HTTP 201'
	$script:ExpenseId = [long]$expenseResponse.Json.id
	Assert-True (
		[long]$expenseResponse.Json.paidByUserId -eq $script:Users['B'] -and
		[long]$expenseResponse.Json.createdByUserId -eq $script:Users['A']) 'Expense response identifies payer B and creator A'
	Verify-InitialExpense

	$countsBeforeSuggestions = Get-ExpenseCounts -GroupId $script:GroupId
	$suggestionResponse = Invoke-LocalApi -Method GET `
		-Path "/api/groups/$($script:GroupId)/settlement-suggestions" -Token $script:Tokens['A']
	Assert-HttpStatus $suggestionResponse 200 'group member can retrieve settlement suggestions'
	$suggestions = @($suggestionResponse.Json.suggestions)
	Assert-True (
		[long]$suggestionResponse.Json.groupId -eq $script:GroupId -and
		$suggestionResponse.Json.currencyCode -eq 'INR' -and
		[decimal]$suggestionResponse.Json.totalSuggestedAmount -eq [decimal]600.00 -and
		$suggestions.Count -eq 2) 'suggestion response has the expected group, currency, total and transfer count'
	Assert-True (
		[long]$suggestions[0].fromUserId -eq $script:Users['A'] -and
		[long]$suggestions[0].toUserId -eq $script:Users['B'] -and
		[decimal]$suggestions[0].amount -eq [decimal]400.00) 'largest debtor A is matched first to creditor B'
	Assert-True (
		[long]$suggestions[1].fromUserId -eq $script:Users['C'] -and
		[long]$suggestions[1].toUserId -eq $script:Users['B'] -and
		[decimal]$suggestions[1].amount -eq [decimal]200.00) 'remaining debtor C is matched to creditor B'
	$countsAfterSuggestions = Get-ExpenseCounts -GroupId $script:GroupId
	Assert-True (
		$countsBeforeSuggestions.Expenses -eq $countsAfterSuggestions.Expenses -and
		$countsBeforeSuggestions.Splits -eq $countsAfterSuggestions.Splits -and
		$countsBeforeSuggestions.Settlements -eq $countsAfterSuggestions.Settlements) `
		'settlement suggestions do not write financial records'

	$expenseList = Invoke-LocalApi -Method GET `
		-Path "/api/groups/$($script:GroupId)/expenses" -Token $script:Tokens['C']
	Assert-HttpStatus $expenseList 200 'group member can list group expenses'
	$listedExpenses = @($expenseList.Json)
	Assert-True (
		$listedExpenses.Count -eq 1 -and [long]$listedExpenses[0].id -eq $script:ExpenseId) `
		'group expense listing contains the Dinner expense'

	$outsiderList = Invoke-LocalApi -Method GET `
		-Path "/api/groups/$($script:GroupId)/expenses" -Token $script:Tokens['D']
	Assert-HttpStatus $outsiderList 404 'unrelated User D cannot list group expenses'
	$outsiderCreate = Invoke-LocalApi -Method POST `
		-Path "/api/groups/$($script:GroupId)/expenses" -Token $script:Tokens['D'] -Body $expenseBody
	Assert-HttpStatus $outsiderCreate 404 'unrelated User D cannot create group expenses'
	$outsiderSuggestions = Invoke-LocalApi -Method GET `
		-Path "/api/groups/$($script:GroupId)/settlement-suggestions" -Token $script:Tokens['D']
	Assert-HttpStatus $outsiderSuggestions 404 'unrelated User D cannot retrieve private suggestions'
	$unauthenticated = Invoke-LocalApi -Method GET `
		-Path "/api/groups/$($script:GroupId)/settlement-suggestions"
	Assert-HttpStatus $unauthenticated 401 'missing authentication is rejected'

	$beforeInvalid = Get-ExpenseCounts -GroupId $script:GroupId
	$invalidExpense = Invoke-LocalApi -Method POST `
		-Path "/api/groups/$($script:GroupId)/expenses" -Token $script:Tokens['A'] -Body @{
			description = 'Invalid split'
			amount = [decimal]10.00
			paidByUserId = $script:Users['B']
			splits = @(@{ userId = $script:Users['A']; amount = [decimal]9.00 })
		}
	Assert-HttpStatus $invalidExpense 400 'incorrect split total is rejected'
	$afterInvalid = Get-ExpenseCounts -GroupId $script:GroupId
	Assert-True (
		$beforeInvalid.Expenses -eq $afterInvalid.Expenses -and
		$beforeInvalid.Splits -eq $afterInvalid.Splits) 'invalid expense creation leaves no partial rows'

	$settlement = Invoke-LocalApi -Method POST `
		-Path "/api/groups/$($script:GroupId)/settlements" -Token $script:Tokens['A'] -Body @{
			fromUserId = $script:Users['A']
			toUserId = $script:Users['B']
			amount = [decimal]100.00
			requestId = $script:SettlementRequestId
		}
	Assert-HttpStatus $settlement 201 'partial settlement is recorded successfully'
	$storedSettlement = Invoke-MySqlScalar -Sql @"
SELECT CONCAT(id, '|', group_id, '|', from_user_id, '|', to_user_id, '|',
              CAST(amount AS CHAR), '|', recorded_by_user_id)
FROM settlements
WHERE group_id = $($script:GroupId)
  AND request_id = '$($script:SettlementRequestId)'
"@
	$settlementParts = $storedSettlement -split '\|', 6
	Assert-True (
		$settlementParts.Count -eq 6 -and
		$settlementParts[1] -eq [string]$script:GroupId -and
		$settlementParts[2] -eq [string]$script:Users['A'] -and
		$settlementParts[3] -eq [string]$script:Users['B'] -and
		$settlementParts[4] -eq '100.00' -and
		$settlementParts[5] -eq [string]$script:Users['A']) 'MySQL stores the exact settlement identity and amount'

	$afterSettlement = Invoke-LocalApi -Method GET `
		-Path "/api/groups/$($script:GroupId)/settlement-suggestions" -Token $script:Tokens['C']
	Assert-HttpStatus $afterSettlement 200 'suggestions remain available after a partial settlement'
	$remainingSuggestions = @($afterSettlement.Json.suggestions)
	Assert-True (
		[decimal]$afterSettlement.Json.totalSuggestedAmount -eq [decimal]500.00 -and
		$remainingSuggestions.Count -eq 2 -and
		[long]$remainingSuggestions[0].fromUserId -eq $script:Users['A'] -and
		[decimal]$remainingSuggestions[0].amount -eq [decimal]300.00 -and
		[long]$remainingSuggestions[1].fromUserId -eq $script:Users['C'] -and
		[decimal]$remainingSuggestions[1].amount -eq [decimal]200.00) `
		'suggestions reflect settlement-adjusted exact outstanding balances'

	$staleSettlement = Invoke-LocalApi -Method POST `
		-Path "/api/groups/$($script:GroupId)/settlements" -Token $script:Tokens['A'] -Body @{
			fromUserId = $script:Users['A']
			toUserId = $script:Users['B']
			amount = [decimal]400.00
			requestId = [Guid]::NewGuid().ToString()
		}
	Assert-HttpStatus $staleSettlement 400 'settlement API rejects an amount exceeding current balances'

	$updatedExpense = Invoke-LocalApi -Method PUT `
		-Path "/api/groups/$($script:GroupId)/expenses/$($script:ExpenseId)" `
		-Token $script:Tokens['A'] -Body @{
			description = 'Dinner'
			amount = [decimal]900.00
			paidByUserId = $script:Users['B']
			splits = @(
				@{ userId = $script:Users['A']; amount = [decimal]200.00 },
				@{ userId = $script:Users['B']; amount = [decimal]400.00 },
				@{ userId = $script:Users['C']; amount = [decimal]300.00 }
			)
		}
	Assert-HttpStatus $updatedExpense 200 'expense edit is accepted by the creator'
	Assert-True (
		[long]$updatedExpense.Json.id -eq $script:ExpenseId -and
		[long]$updatedExpense.Json.createdByUserId -eq $script:Users['A']) `
		'expense edit preserves its ID and creator'
	$afterEdit = Invoke-LocalApi -Method GET `
		-Path "/api/groups/$($script:GroupId)/settlement-suggestions" -Token $script:Tokens['A']
	Assert-HttpStatus $afterEdit 200 'suggestions reflect the updated expense'
	$editedSuggestions = @($afterEdit.Json.suggestions)
	$editedMatches = (
		[decimal]$afterEdit.Json.totalSuggestedAmount -eq [decimal]400.00 -and
		$editedSuggestions.Count -eq 2 -and
		[long]$editedSuggestions[0].fromUserId -eq $script:Users['C'] -and
		[decimal]$editedSuggestions[0].amount -eq [decimal]300.00 -and
		[long]$editedSuggestions[1].fromUserId -eq $script:Users['A'] -and
		[decimal]$editedSuggestions[1].amount -eq [decimal]100.00)
	if (-not $editedMatches) {
		$observedTransfers = @($editedSuggestions | ForEach-Object {
			"from=$($_.fromUserId),to=$($_.toUserId),amount=$($_.amount)"
		}) -join '; '
		throw "Unexpected updated suggestions: total=$($afterEdit.Json.totalSuggestedAmount), count=$($editedSuggestions.Count), $observedTransfers"
	}
	Write-Pass 'expense edit recalculates settlement suggestions'

	$deletedExpense = Invoke-LocalApi -Method DELETE `
		-Path "/api/groups/$($script:GroupId)/expenses/$($script:ExpenseId)" -Token $script:Tokens['A']
	Assert-HttpStatus $deletedExpense 204 'expense creator can delete the disposable expense'
	$afterDeleteCounts = Get-ExpenseCounts -GroupId $script:GroupId
	Assert-True (
		$afterDeleteCounts.Expenses -eq 0 -and $afterDeleteCounts.Splits -eq 0 -and
		$afterDeleteCounts.Settlements -eq 1) 'expense deletion removes only the expense and shares'
	$afterDelete = Invoke-LocalApi -Method GET `
		-Path "/api/groups/$($script:GroupId)/settlement-suggestions" -Token $script:Tokens['B']
	Assert-HttpStatus $afterDelete 200 'suggestions remain available after expense deletion'
	Assert-True (
		[decimal]$afterDelete.Json.totalSuggestedAmount -eq [decimal]100.00 -and
		@($afterDelete.Json.suggestions).Count -eq 1 -and
		[long]$afterDelete.Json.suggestions[0].fromUserId -eq $script:Users['B'] -and
		[long]$afterDelete.Json.suggestions[0].toUserId -eq $script:Users['A']) `
		'remaining settlement alone is reflected after expense deletion'

	$healthAfter = Invoke-LocalApi -Method GET -Path '/api/health'
	Assert-HttpStatus $healthAfter 200 'health endpoint remains HTTP 200 after API verification'
	Confirm-MigrationHistory
}

function Get-RunUsers {
	$found = @()
	foreach ($role in @('A', 'B', 'C', 'D')) {
		if (-not $script:Identities.ContainsKey($role)) {
			continue
		}
		$identity = $script:Identities[$role]
		$rows = @(Invoke-MySql -Sql @"
SELECT CONCAT(id, '|', email, '|', name)
FROM users
WHERE email = '$($identity.Email)'
"@)
		if ($rows.Count -gt 1) {
			throw 'A unique disposable email matched multiple database users; cleanup refused.'
		}
		if ($rows.Count -eq 1) {
			$parts = $rows[0] -split '\|', 3
			if ($parts.Count -ne 3 -or $parts[1] -ne $identity.Email -or $parts[2] -ne $identity.Name) {
				throw 'A disposable user identity does not match the generated verification identity; cleanup refused.'
			}
			if ($null -ne $identity.Id -and [long]$parts[0] -ne [long]$identity.Id) {
				throw 'A disposable user ID does not match its API response; cleanup refused.'
			}
			$found += [pscustomobject]@{ Id = [long]$parts[0]; Email = $parts[1]; Name = $parts[2] }
		}
	}
	return @($found)
}

function Remove-VerifiedRunData {
	$users = @(Get-RunUsers)
	$ids = @($users | ForEach-Object { [long]$_.Id })
	$group = $null
	if ($script:Identities.ContainsKey('A') -and $null -ne $script:Identities['A'].Id) {
		$groupRows = @(Invoke-MySql -Sql @"
SELECT CONCAT(id, '|', name, '|', created_by)
FROM expense_groups
WHERE name = '$($script:GroupName)'
  AND created_by = $($script:Identities['A'].Id)
"@)
		if ($groupRows.Count -gt 1) {
			throw 'Generated group identity is not unique; cleanup refused.'
		}
		if ($groupRows.Count -eq 1) {
			$parts = $groupRows[0] -split '\|', 3
			if ($parts.Count -ne 3 -or $parts[1] -ne $script:GroupName -or
				[long]$parts[2] -ne [long]$script:Identities['A'].Id -or
				($null -ne $script:GroupId -and [long]$parts[0] -ne [long]$script:GroupId)) {
				throw 'Generated group owner or ID does not match the API response; cleanup refused.'
			}
			$group = [long]$parts[0]
		}
	}

	if ($null -ne $group) {
		$memberRows = @(Invoke-MySql -Sql @"
SELECT user_id
FROM group_members
WHERE group_id = $group
ORDER BY user_id
"@)
		$allowedMemberIds = @(
			@($script:Users['A'], $script:Users['B'], $script:Users['C']) |
				Where-Object { $null -ne $_ } |
				ForEach-Object { [long]$_ } |
				Sort-Object -Unique
		)
		$actualMemberIds = @($memberRows | ForEach-Object { [long]$_ })
		foreach ($memberId in $actualMemberIds) {
			if ($memberId -notin $allowedMemberIds) {
				throw 'The generated group has an unexpected member; cleanup refused.'
			}
		}
		if ($actualMemberIds.Count -gt 0 -and $actualMemberIds -notcontains [long]$script:Users['A']) {
			throw 'The generated group is missing its expected owner membership; cleanup refused.'
		}

		$expenseRows = @(Invoke-MySql -Sql @"
SELECT CONCAT(id, '|', description, '|', created_by_user_id, '|', paid_by_user_id)
FROM expenses
WHERE group_id = $group
"@)
		if ($expenseRows.Count -gt 1) {
			throw 'The generated group contains unexpected expenses; cleanup refused.'
		}
		if ($expenseRows.Count -eq 1) {
			$parts = $expenseRows[0] -split '\|', 4
			if ($parts.Count -ne 4 -or [long]$parts[0] -ne [long]$script:ExpenseId -or
				$parts[1] -ne 'Dinner' -or [long]$parts[2] -ne [long]$script:Users['A'] -or
				[long]$parts[3] -ne [long]$script:Users['B']) {
				throw 'An expense identity or dependency does not match this run; cleanup refused.'
			}
			$expenseId = [long]$parts[0]
			$splitRows = @(Invoke-MySql -Sql @"
SELECT user_id
FROM expense_splits
WHERE expense_id = $expenseId
ORDER BY user_id
"@)
			$actualSplitIds = @($splitRows | ForEach-Object { [long]$_ } | Sort-Object)
			$expectedSplitIds = @(
				@([long]$script:Users['A'], [long]$script:Users['B'], [long]$script:Users['C']) |
					Sort-Object
			)
			if ($actualSplitIds.Count -ne 3 -or
				($actualSplitIds -join ',') -ne ($expectedSplitIds -join ',')) {
				throw 'Expense split identities differ from this run; cleanup refused.'
			}
			$deletedSplits = Invoke-MySqlScalar -Sql @"
DELETE FROM expense_splits WHERE expense_id = $expenseId;
SELECT ROW_COUNT()
"@
			if ([int]$deletedSplits -ne 3) {
				throw 'Exact expense split cleanup did not remove the expected three rows.'
			}
			$deletedExpense = Invoke-MySqlScalar -Sql @"
DELETE FROM expenses
WHERE id = $expenseId AND group_id = $group AND description = 'Dinner'
  AND created_by_user_id = $($script:Users['A'])
  AND paid_by_user_id = $($script:Users['B']);
SELECT ROW_COUNT()
"@
			if ([int]$deletedExpense -ne 1) {
				throw 'Exact disposable expense cleanup did not remove one row.'
			}
		}

		$settlementRows = @(Invoke-MySql -Sql @"
SELECT CONCAT(id, '|', request_id, '|', from_user_id, '|', to_user_id, '|',
              CAST(amount AS CHAR), '|', recorded_by_user_id)
FROM settlements
WHERE group_id = $group
"@)
		if ($settlementRows.Count -gt 1) {
			throw 'The generated group contains unexpected settlements; cleanup refused.'
		}
		if ($settlementRows.Count -eq 1) {
			$parts = $settlementRows[0] -split '\|', 6
			if ($parts.Count -ne 6 -or $parts[1] -ne $script:SettlementRequestId -or
				[long]$parts[2] -ne [long]$script:Users['A'] -or
				[long]$parts[3] -ne [long]$script:Users['B'] -or $parts[4] -ne '100.00' -or
				[long]$parts[5] -ne [long]$script:Users['A']) {
				throw 'A settlement identity or dependency does not match this run; cleanup refused.'
			}
			$settlementId = [long]$parts[0]
			$deletedSettlement = Invoke-MySqlScalar -Sql @"
DELETE FROM settlements
WHERE id = $settlementId AND group_id = $group
  AND request_id = '$($script:SettlementRequestId)'
  AND from_user_id = $($script:Users['A'])
  AND to_user_id = $($script:Users['B'])
  AND recorded_by_user_id = $($script:Users['A'])
  AND amount = 100.00;
SELECT ROW_COUNT()
"@
			if ([int]$deletedSettlement -ne 1) {
				throw 'Exact disposable settlement cleanup did not remove one row.'
			}
		}

		if ($actualMemberIds.Count -gt 0) {
			$memberIdList = $actualMemberIds -join ','
			$deletedMembers = Invoke-MySqlScalar -Sql @"
DELETE FROM group_members
WHERE group_id = $group AND user_id IN ($memberIdList);
SELECT ROW_COUNT()
"@
			if ([int]$deletedMembers -ne $actualMemberIds.Count) {
				throw 'Exact group membership cleanup did not remove the expected rows.'
			}
		}
		$remainingGroupDependencies = Invoke-MySqlScalar -Sql @"
SELECT
 (SELECT COUNT(*) FROM group_members WHERE group_id = $group) +
 (SELECT COUNT(*) FROM expenses WHERE group_id = $group) +
 (SELECT COUNT(*) FROM settlements WHERE group_id = $group)
"@
		if ([int]$remainingGroupDependencies -ne 0) {
			throw 'The generated group still has dependent records; cleanup refused.'
		}
		$deletedGroup = Invoke-MySqlScalar -Sql @"
DELETE FROM expense_groups
WHERE id = $group AND name = '$($script:GroupName)'
  AND created_by = $($script:Users['A']);
SELECT ROW_COUNT()
"@
		if ([int]$deletedGroup -ne 1) {
			throw 'Exact disposable group cleanup did not remove one row.'
		}
	}

	if ($ids.Count -gt 0) {
		$idList = $ids -join ','
		$remainingReferences = Invoke-MySqlScalar -Sql @"
SELECT
 (SELECT COUNT(*) FROM expense_groups WHERE created_by IN ($idList)) +
 (SELECT COUNT(*) FROM group_members WHERE user_id IN ($idList)) +
 (SELECT COUNT(*) FROM expenses WHERE paid_by_user_id IN ($idList) OR created_by_user_id IN ($idList)) +
 (SELECT COUNT(*) FROM expense_splits WHERE user_id IN ($idList)) +
 (SELECT COUNT(*) FROM settlements WHERE from_user_id IN ($idList) OR to_user_id IN ($idList)
    OR recorded_by_user_id IN ($idList))
"@
		if ([int]$remainingReferences -ne 0) {
			throw 'Disposable users still have foreign-key references; cleanup refused.'
		}
		foreach ($user in $users) {
			$deletedUser = Invoke-MySqlScalar -Sql @"
DELETE FROM users WHERE id = $($user.Id) AND email = '$($user.Email)';
SELECT ROW_COUNT()
"@
			if ([int]$deletedUser -ne 1) {
				throw 'Exact disposable user cleanup did not remove one row.'
			}
		}
	}

	$remainingUserCount = @(Get-RunUsers).Count
	$remainingSettlementCount = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*) FROM settlements WHERE request_id = '$($script:SettlementRequestId)'
"@
	$remainingGroupCount = '0'
	$remainingMembershipCount = '0'
	if ($null -ne $group) {
		$remainingGroupCount = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*) FROM expense_groups WHERE id = $group AND name = '$($script:GroupName)'
"@
		$remainingMembershipCount = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*) FROM group_members WHERE group_id = $group
"@
	}
	$remainingExpenseCount = '0'
	$remainingSplitCount = '0'
	if ($null -ne $script:ExpenseId) {
		$remainingExpenseCount = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*) FROM expenses WHERE id = $($script:ExpenseId)
"@
		$remainingSplitCount = Invoke-MySqlScalar -Sql @"
SELECT COUNT(*) FROM expense_splits WHERE expense_id = $($script:ExpenseId)
"@
	}
	if ($remainingUserCount -ne 0 -or [int]$remainingSettlementCount -ne 0 -or
		[int]$remainingGroupCount -ne 0 -or [int]$remainingMembershipCount -ne 0 -or
		[int]$remainingExpenseCount -ne 0 -or [int]$remainingSplitCount -ne 0) {
		throw 'Read-only cleanup verification found remaining records from this run.'
	}
	Write-Pass 'read-only queries confirm all disposable users, group, expense, splits and settlement are absent'
}

function Write-RemainingRunRecords {
	try {
		$users = @(Get-RunUsers)
		foreach ($user in $users) {
			Write-Host "REMAINING disposable user: id=$($user.Id), email=$($user.Email)"
		}
		$groupIdToInspect = $script:GroupId
		if ($null -eq $groupIdToInspect -and
			$script:Identities.ContainsKey('A') -and
			$null -ne $script:Identities['A'].Id) {
			$groupRows = @(Invoke-MySql -Sql @"
SELECT CONCAT(id, '|', name)
FROM expense_groups
WHERE name = '$($script:GroupName)'
  AND created_by = $($script:Identities['A'].Id)
"@)
  if ($groupRows.Count -eq 1) {
	$groupIdToInspect = [long](($groupRows[0] -split '\|', 2)[0])
			}
  else {
	foreach ($row in $groupRows) {
		Write-Host "REMAINING disposable group: $row"
	}
  }
		}
		if ($null -ne $groupIdToInspect) {
			$groupRows = @(Invoke-MySql -Sql @"
SELECT CONCAT(id, '|', name)
FROM expense_groups
WHERE id = $groupIdToInspect AND name = '$($script:GroupName)'
"@)
			foreach ($row in $groupRows) {
				Write-Host "REMAINING disposable group: $row"
			}
			$expenseRows = @(Invoke-MySql -Sql @"
SELECT CONCAT(id, '|', description) FROM expenses WHERE group_id = $groupIdToInspect
"@)
			foreach ($row in $expenseRows) {
				Write-Host "REMAINING disposable expense: $row"
			}
			$settlementRows = @(Invoke-MySql -Sql @"
SELECT CONCAT(id, '|', request_id) FROM settlements WHERE group_id = $groupIdToInspect
"@)
			foreach ($row in $settlementRows) {
				Write-Host "REMAINING disposable settlement: $row"
			}
		}
	}
	catch {
		Write-Host 'Could not enumerate remaining disposable records; local database inspection failed.'
	}
}

try {
	Write-Host "Verification run ID: $($script:RunId)"
	Read-LocalSettings
	Confirm-LocalInstances
	Confirm-MigrationHistory
	Invoke-LiveScenario
}
catch {
	if ($null -eq $script:MainFailure) {
		$script:MainFailure = $_.Exception.Message
	}
	if ($script:Failures -eq 0) {
		$script:Failures++
	}
	Write-Host "FAIL: $($script:MainFailure)" -ForegroundColor Red
}
finally {
	if ($script:Identities.Count -eq 0 -and $null -eq $script:GroupId) {
		Write-Pass 'no disposable verification records were created'
	}
	else {
		try {
			Remove-VerifiedRunData
			Write-Pass 'exactly identified verification records were safely cleaned up'
		}
		catch {
			$script:CleanupFailure = $_.Exception.Message
			$script:Failures++
			Write-Host "FAIL: cleanup refused or incomplete: $($script:CleanupFailure)" -ForegroundColor Red
			Write-RemainingRunRecords
		}
	}
	[Environment]::SetEnvironmentVariable('MYSQL_PWD', $script:OriginalMysqlPassword, 'Process')
}

if ($null -ne $script:MainFailure -or $null -ne $script:CleanupFailure -or $script:Failures -gt 0) {
	Write-Host "Summary: $($script:Passes) passed, $($script:Failures) failed."
	exit 1
}

Write-Host "Summary: $($script:Passes) passed, 0 failed."
