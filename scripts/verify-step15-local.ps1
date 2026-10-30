$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $repositoryRoot 'backend'
$environmentFile = Join-Path $backendDirectory '.env.local'
$javaHome = Join-Path $HOME '.jdks\temurin-21'
$javaExecutable = Join-Path $javaHome 'bin\java.exe'
$jarPath = Join-Path $backendDirectory 'target\splitsmart-backend-0.0.1-SNAPSHOT.jar'
$mysqlDirectory = Join-Path $HOME 'mysql\mysql-8.4.13\bin'
$mysqlExecutable = Join-Path $mysqlDirectory 'mysql.exe'
$mysqlServerExecutable = Join-Path $mysqlDirectory 'mysqld.exe'
$mysqlConfiguration = Join-Path $HOME 'mysql\splitsmart\my.ini'
$baseUrl = 'http://127.0.0.1:8081'
$runId = [Guid]::NewGuid().ToString('N')
$groupName = "Step15-$runId"
$scenarioStage = 'initializing'
$databaseAvailable = $false
$backendProcess = $null
$groupId = $null
$expenseId = $null
$expectedMemberIds = @()
$users = @()
$settlementSpecs = @()
$raceJobs = @()
$failure = $null
$cleanupFailure = $null
$stdoutLog = Join-Path $env:TEMP "splitsmart-step15-$runId.out.log"
$stderrLog = Join-Path $env:TEMP "splitsmart-step15-$runId.err.log"
$environmentNames = @('JAVA_HOME', 'PATH', 'MYSQL_PWD', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET')
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
	$originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Set-Stage([string]$stage) {
	$script:scenarioStage = $stage
}

function Assert-Condition([bool]$condition, [string]$message) {
	if (-not $condition) {
		throw $message
	}
}

function Invoke-MySql([string]$statement) {
	$output = @(& $script:mysqlExecutable `
		--no-defaults `
		--protocol=TCP `
		--host=127.0.0.1 `
		--port=3306 `
		"--user=$($script:databaseUser)" `
		--database=splitsmart_dev `
		--batch `
		--raw `
		--skip-column-names `
		--execute $statement 2>&1)
	if ($LASTEXITCODE -ne 0) {
		throw 'The local MySQL client reported a query failure.'
	}
	return $output
}

function Invoke-Api([string]$method, [string]$path, [string]$token, [object]$body) {
	$headers = @{ Accept = 'application/json' }
	if (-not [string]::IsNullOrWhiteSpace($token)) {
		$headers.Authorization = "Bearer $token"
	}
	$request = @{
		Uri = "$($script:baseUrl)$path"
		Method = $method
		Headers = $headers
		UseBasicParsing = $true
		ErrorAction = 'Stop'
	}
	if ($null -ne $body) {
		$request.ContentType = 'application/json'
		$request.Body = ConvertTo-Json -InputObject $body -Depth 10 -Compress
	}
	try {
		$response = Invoke-WebRequest @request
		return [pscustomobject]@{
			StatusCode = [int]$response.StatusCode
			Body = [string]$response.Content
		}
	}
	catch {
		$webResponse = $_.Exception.Response
		if ($null -eq $webResponse) {
			throw 'The local API request could not be completed.'
		}
		$reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
		try {
			$responseBody = $reader.ReadToEnd()
		}
		finally {
			$reader.Dispose()
		}
		return [pscustomobject]@{
			StatusCode = [int]$webResponse.StatusCode
			Body = $responseBody
		}
	}
}

function Assert-ApiStatus([object]$response, [int]$expected, [string]$operation) {
	Assert-Condition ($response.StatusCode -eq $expected) `
		"$operation returned HTTP $($response.StatusCode), expected $expected."
}

function Read-Money([object]$value) {
	return [decimal]::Parse(
		[Convert]::ToString($value, [Globalization.CultureInfo]::InvariantCulture),
		[Globalization.NumberStyles]::Number,
		[Globalization.CultureInfo]::InvariantCulture)
}

function Assert-Money([object]$actual, [string]$expected, [string]$label) {
	$expectedValue = [decimal]::Parse(
		$expected,
		[Globalization.NumberStyles]::Number,
		[Globalization.CultureInfo]::InvariantCulture)
	Assert-Condition ((Read-Money $actual) -eq $expectedValue) "$label was not exact."
}

function Assert-GroupBalances(
	[string]$json,
	[string]$expectedA,
	[string]$expectedB,
	[string]$expectedC,
	[string]$sentA = '0.00',
	[string]$sentB = '0.00',
	[string]$sentC = '0.00',
	[string]$receivedA = '0.00',
	[string]$receivedB = '0.00',
	[string]$receivedC = '0.00'
) {
	$result = $json | ConvertFrom-Json
	Assert-Condition ($result.currencyCode -eq 'INR') 'Balance currency was not INR.'
	Assert-Money $result.totalExpenseAmount '900.00' 'Total expense amount'
	Assert-Condition (@($result.balances).Count -eq 3) 'Expected exactly three group balances.'
	$expected = @($expectedA, $expectedB, $expectedC)
	$expectedSent = @($sentA, $sentB, $sentC)
	$expectedReceived = @($receivedA, $receivedB, $receivedC)
	for ($index = 0; $index -lt 3; $index++) {
		Assert-Condition ([long]$result.balances[$index].userId -eq $script:expectedMemberIds[$index]) `
			'Balance members were not returned in expected user ID order.'
		Assert-Money $result.balances[$index].netBalance $expected[$index] `
			"User $($script:expectedMemberIds[$index]) net balance"
		Assert-Money $result.balances[$index].totalSentSettlements $expectedSent[$index] `
			"User $($script:expectedMemberIds[$index]) sent settlements"
		Assert-Money $result.balances[$index].totalReceivedSettlements $expectedReceived[$index] `
			"User $($script:expectedMemberIds[$index]) received settlements"
	}
	$netTotal = @($result.balances | ForEach-Object { Read-Money $_.netBalance } |
		Measure-Object -Sum).Sum
	Assert-Condition ([decimal]$netTotal -eq [decimal]::Zero) 'Net balances did not sum to zero.'
}

function New-SettlementSpec(
	[object]$from,
	[object]$to,
	[string]$amount,
	[object]$note
) {
	return [pscustomobject]@{
		Id = $null
		GroupId = [long]$script:groupId
		FromUserId = [long]$from.Id
		ToUserId = [long]$to.Id
		Amount = $amount
		RecordedByUserId = [long]$from.Id
		RequestId = [Guid]::NewGuid().ToString()
		Note = $note
		Created = $false
	}
}

function Get-ExpectedSettlementRow([object]$settlement) {
	$note = if ($null -eq $settlement.Note) { '<NULL>' } else { $settlement.Note }
	return "{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}`t{7}" -f `
		$settlement.Id, $settlement.GroupId, $settlement.FromUserId, $settlement.ToUserId,
		$settlement.Amount, $settlement.RecordedByUserId, $settlement.RequestId, $note
}

function Get-SettlementRows {
	return @(Invoke-MySql (
		"SELECT CONCAT(id,CHAR(9),group_id,CHAR(9),from_user_id,CHAR(9),to_user_id," +
		"CHAR(9),CAST(amount AS CHAR),CHAR(9),recorded_by_user_id,CHAR(9),request_id," +
		"CHAR(9),COALESCE(note,'<NULL>')) FROM settlements WHERE group_id=$($script:groupId) " +
		'ORDER BY id'))
}

function Resolve-DisposableIdentities {
	Set-Stage 'cleanup: resolving disposable user identities'
	foreach ($user in $script:users) {
		$matchingUsers = @(Invoke-MySql (
			"SELECT CONCAT(id,CHAR(9),name,CHAR(9),email) FROM users " +
			"WHERE name='$($user.Name)' AND email='$($user.Email)'"))
		if ($matchingUsers.Count -gt 1) {
			throw 'A disposable user identity matched more than one database row.'
		}
		if ($matchingUsers.Count -eq 1) {
			$fields = $matchingUsers[0] -split "`t"
			if ($null -ne $user.Id -and [long]$fields[0] -ne [long]$user.Id) {
				throw 'A tracked disposable user ID no longer matches its unique identity.'
			}
			$user.Id = [long]$fields[0]
		}
		elseif ($null -ne $user.Id) {
			throw 'A tracked disposable user identity is missing from the database.'
		}
	}

	if ($null -eq $script:groupId -and $script:users.Count -gt 0 -and
		$null -ne $script:users[0].Id) {
		$groups = @(Invoke-MySql (
			"SELECT id FROM expense_groups WHERE name='$($script:groupName)' " +
			"AND created_by=$($script:users[0].Id)"))
		if ($groups.Count -gt 1) {
			throw 'The unique verification group identity matched multiple rows.'
		}
		if ($groups.Count -eq 1) {
			$script:groupId = [long]$groups[0]
		}
	}
	if ($null -eq $script:groupId) {
		return
	}

	Set-Stage 'cleanup: verifying exact group and member identities'
	$groupRows = @(Invoke-MySql (
		"SELECT CONCAT(id,CHAR(9),name,CHAR(9),created_by) FROM expense_groups " +
		"WHERE id=$($script:groupId) AND name='$($script:groupName)' " +
		"AND created_by=$($script:users[0].Id)"))
	Assert-Condition ($groupRows.Count -eq 1) 'The tracked verification group identity did not match.'

	$members = @(Invoke-MySql (
		"SELECT user_id FROM group_members WHERE group_id=$($script:groupId) ORDER BY user_id"))
	$allowedMemberIds = @($script:expectedMemberIds | ForEach-Object { [string]$_ } | Sort-Object -Unique)
	$actualMemberIds = @($members | ForEach-Object { [string]$_ } | Sort-Object -Unique)
	Assert-Condition ((@(Compare-Object $actualMemberIds $allowedMemberIds)).Count -eq 0) `
		'Verification group memberships differ from the tracked records.'

	if ($null -eq $script:expenseId) {
		$matchingExpenses = @(Invoke-MySql (
			"SELECT id FROM expenses WHERE group_id=$($script:groupId) " +
			"AND description='Dinner' AND amount=900.00 AND currency_code='INR' " +
			"AND paid_by_user_id=$($script:users[0].Id) " +
			"AND created_by_user_id=$($script:users[0].Id)"))
		if ($matchingExpenses.Count -gt 1) {
			throw 'The disposable expense identity matched multiple rows.'
		}
		if ($matchingExpenses.Count -eq 1) {
			$script:expenseId = [long]$matchingExpenses[0]
		}
	}
	$groupExpenses = @(Invoke-MySql (
		"SELECT CONCAT(id,CHAR(9),group_id,CHAR(9),description,CHAR(9),CAST(amount AS CHAR)," +
		"CHAR(9),currency_code,CHAR(9),paid_by_user_id,CHAR(9),created_by_user_id) " +
		"FROM expenses WHERE group_id=$($script:groupId)"))
	if ($null -eq $script:expenseId) {
		Assert-Condition ($groupExpenses.Count -eq 0) `
			'The verification group contains an expense without a tracked exact identity.'
		$actualSplits = @()
	}
	else {
		$expectedExpense = "{0}`t{1}`tDinner`t900.00`tINR`t{2}`t{2}" -f `
			$script:expenseId, $script:groupId, $script:users[0].Id
		Assert-Condition ($groupExpenses.Count -eq 1 -and $groupExpenses[0] -eq $expectedExpense) `
			'Expense rows in the verification group differ from the tracked expense.'
		$actualSplits = @(Invoke-MySql (
			"SELECT CONCAT(user_id,CHAR(9),CAST(amount AS CHAR)) FROM expense_splits " +
			"WHERE expense_id=$($script:expenseId) ORDER BY user_id"))
		$expectedSplits = @(
			"$($script:users[0].Id)`t300.00",
			"$($script:users[1].Id)`t300.00",
			"$($script:users[2].Id)`t300.00"
		) | Sort-Object
		Assert-Condition ((@(Compare-Object $actualSplits $expectedSplits)).Count -eq 0) `
			'Expense split rows differ from the exact tracked shares.'
	}

	Set-Stage 'cleanup: reconciling settlement request identities'
	foreach ($settlement in $script:settlementSpecs) {
		$matchingRows = @(Invoke-MySql (
			"SELECT CONCAT(id,CHAR(9),group_id,CHAR(9),from_user_id,CHAR(9),to_user_id," +
			"CHAR(9),CAST(amount AS CHAR),CHAR(9),recorded_by_user_id,CHAR(9),request_id," +
			"CHAR(9),COALESCE(note,'<NULL>')) FROM settlements WHERE group_id=$($script:groupId) " +
			"AND request_id='$($settlement.RequestId)'"))
		if ($matchingRows.Count -gt 1) {
			throw 'A disposable settlement request identity matched multiple rows.'
		}
		if ($matchingRows.Count -eq 0) {
			if ($settlement.Created) {
				throw 'A settlement reported as created is missing from the database.'
			}
			continue
		}
		$fields = $matchingRows[0] -split "`t"
		$note = if ($null -eq $settlement.Note) { '<NULL>' } else { $settlement.Note }
		$expectedTail = "{0}`t{1}`t{2}`t{3}`t{4}`t{5}`t{6}" -f `
			$settlement.GroupId, $settlement.FromUserId, $settlement.ToUserId,
			$settlement.Amount, $settlement.RecordedByUserId, $settlement.RequestId, $note
		$actualTail = if ($fields.Count -eq 8) { $fields[1..7] -join "`t" } else { '' }
		if ($fields.Count -ne 8 -or $actualTail -cne $expectedTail) {
			throw "Settlement request identity $($settlement.RequestId) differed: expected [$expectedTail], stored [$actualTail]."
		}
		if ($null -ne $settlement.Id -and [long]$fields[0] -ne [long]$settlement.Id) {
			throw 'A tracked settlement ID no longer matches its unique request identity.'
		}
		$settlement.Id = [long]$fields[0]
		$settlement.Created = $true
	}
	$actualSettlements = @(Get-SettlementRows)
	$expectedSettlements = @($script:settlementSpecs |
		Where-Object { $_.Created } |
		ForEach-Object { Get-ExpectedSettlementRow $_ } | Sort-Object)
	$actualSettlements = @($actualSettlements | Sort-Object)
	Assert-Condition ((@(Compare-Object $actualSettlements $expectedSettlements)).Count -eq 0) `
		'Settlement rows in the verification group differ from the exact tracked records.'

	foreach ($user in $script:users) {
		if ($null -eq $user.Id) {
			continue
		}
		$otherMemberships = @(Invoke-MySql (
			"SELECT id FROM group_members WHERE user_id=$($user.Id) " +
			"AND group_id<>$($script:groupId)"))
		$otherGroups = @(Invoke-MySql (
			"SELECT id FROM expense_groups WHERE created_by=$($user.Id) " +
			"AND id<>$($script:groupId)"))
		if ($null -eq $script:expenseId) {
			$otherExpenses = @(Invoke-MySql (
				"SELECT id FROM expenses WHERE paid_by_user_id=$($user.Id) " +
				"OR created_by_user_id=$($user.Id)"))
			$otherSplits = @(Invoke-MySql "SELECT id FROM expense_splits WHERE user_id=$($user.Id)")
		}
		else {
			$otherExpenses = @(Invoke-MySql (
				"SELECT id FROM expenses WHERE (paid_by_user_id=$($user.Id) " +
				"OR created_by_user_id=$($user.Id)) AND id<>$($script:expenseId)"))
			$otherSplits = @(Invoke-MySql (
				"SELECT id FROM expense_splits WHERE user_id=$($user.Id) " +
				"AND expense_id<>$($script:expenseId)"))
		}
		$otherSettlements = @(Invoke-MySql (
			"SELECT id FROM settlements WHERE (from_user_id=$($user.Id) " +
			"OR to_user_id=$($user.Id) OR recorded_by_user_id=$($user.Id)) " +
			"AND group_id<>$($script:groupId)"))
		Assert-Condition (
			$otherMemberships.Count -eq 0 -and $otherGroups.Count -eq 0 -and
			$otherExpenses.Count -eq 0 -and $otherSplits.Count -eq 0 -and
			$otherSettlements.Count -eq 0) `
			"A disposable user $($user.Id) has dependencies outside the verification group."
	}
}

function Remove-DisposableRecords {
	Resolve-DisposableIdentities
	if ($null -eq $script:groupId) {
		foreach ($user in $script:users) {
			if ($null -eq $user.Id) {
				continue
			}
			$dependencies = @(Invoke-MySql (
				"SELECT (SELECT COUNT(*) FROM group_members WHERE user_id=$($user.Id))+" +
				"(SELECT COUNT(*) FROM expense_groups WHERE created_by=$($user.Id))+" +
				"(SELECT COUNT(*) FROM expenses WHERE paid_by_user_id=$($user.Id) " +
				"OR created_by_user_id=$($user.Id))+" +
				"(SELECT COUNT(*) FROM expense_splits WHERE user_id=$($user.Id))+" +
				"(SELECT COUNT(*) FROM settlements WHERE from_user_id=$($user.Id) " +
				"OR to_user_id=$($user.Id) OR recorded_by_user_id=$($user.Id))"))
			Assert-Condition ($dependencies.Count -eq 1 -and $dependencies[0] -eq '0') `
				"A disposable user $($user.Id) still has dependent records; cleanup stopped."
			[void](Invoke-MySql (
				"DELETE FROM users WHERE id=$($user.Id) AND name='$($user.Name)' " +
				"AND email='$($user.Email)'"))
			$remaining = @(Invoke-MySql "SELECT id FROM users WHERE id=$($user.Id)")
			Assert-Condition ($remaining.Count -eq 0) "Disposable user $($user.Id) remains."
		}
		return
	}

	$settlementIds = @($script:settlementSpecs |
		Where-Object { $_.Created } |
		ForEach-Object { [long]$_.Id })
	if ($settlementIds.Count -gt 0) {
		Set-Stage 'cleanup: deleting exact settlement rows'
		foreach ($settlement in @($script:settlementSpecs | Where-Object { $_.Created })) {
			$notePredicate = if ($null -eq $settlement.Note) {
				'note IS NULL'
			}
			else {
				"note='$($settlement.Note)'"
			}
			[void](Invoke-MySql (
				"DELETE FROM settlements WHERE id=$($settlement.Id) " +
				"AND group_id=$($script:groupId) AND from_user_id=$($settlement.FromUserId) " +
				"AND to_user_id=$($settlement.ToUserId) AND amount=$($settlement.Amount) " +
				"AND recorded_by_user_id=$($settlement.RecordedByUserId) " +
				"AND request_id='$($settlement.RequestId)' AND $notePredicate"))
		}
		Assert-Condition ((@(Get-SettlementRows)).Count -eq 0) 'Verification settlements remain after deletion.'
	}
	if ($null -ne $script:expenseId) {
		Set-Stage 'cleanup: deleting exact expense rows'
		[void](Invoke-MySql "DELETE FROM expense_splits WHERE expense_id=$($script:expenseId)")
		$remainingSplits = @(Invoke-MySql "SELECT id FROM expense_splits WHERE expense_id=$($script:expenseId)")
		Assert-Condition ($remainingSplits.Count -eq 0) 'Verification expense splits remain.'
		Set-Stage 'cleanup: deleting exact group and memberships'
		[void](Invoke-MySql (
			"DELETE FROM expenses WHERE id=$($script:expenseId) AND group_id=$($script:groupId) " +
			"AND description='Dinner' AND amount=900.00 AND currency_code='INR' " +
			"AND paid_by_user_id=$($script:users[0].Id) " +
			"AND created_by_user_id=$($script:users[0].Id)"))
		$remainingExpense = @(Invoke-MySql (
			"SELECT id FROM expenses WHERE id=$($script:expenseId) AND group_id=$($script:groupId)"))
		Assert-Condition ($remainingExpense.Count -eq 0) 'Verification expense remains.'
	}
	[void](Invoke-MySql (
		"DELETE FROM group_members WHERE group_id=$($script:groupId) " +
		"AND user_id IN ($($script:expectedMemberIds -join ','))"))
	$remainingMembers = @(Invoke-MySql "SELECT id FROM group_members WHERE group_id=$($script:groupId)")
	Assert-Condition ($remainingMembers.Count -eq 0) 'Verification group memberships remain.'
	[void](Invoke-MySql (
		"DELETE FROM expense_groups WHERE id=$($script:groupId) " +
		"AND name='$($script:groupName)' AND created_by=$($script:users[0].Id)"))
	$remainingGroup = @(Invoke-MySql "SELECT id FROM expense_groups WHERE id=$($script:groupId)")
	Assert-Condition ($remainingGroup.Count -eq 0) 'Verification group remains.'

	foreach ($user in $script:users) {
		if ($null -eq $user.Id) {
			continue
		}
		Set-Stage "cleanup: confirming no remaining dependencies for disposable user $($user.Id)"
		$dependencies = @(Invoke-MySql (
			"SELECT (SELECT COUNT(*) FROM group_members WHERE user_id=$($user.Id))+" +
			"(SELECT COUNT(*) FROM expense_groups WHERE created_by=$($user.Id))+" +
			"(SELECT COUNT(*) FROM expenses WHERE paid_by_user_id=$($user.Id) " +
			"OR created_by_user_id=$($user.Id))+" +
			"(SELECT COUNT(*) FROM expense_splits WHERE user_id=$($user.Id))+" +
			"(SELECT COUNT(*) FROM settlements WHERE from_user_id=$($user.Id) " +
			"OR to_user_id=$($user.Id) OR recorded_by_user_id=$($user.Id))"))
		Assert-Condition ($dependencies.Count -eq 1 -and $dependencies[0] -eq '0') `
			"A disposable user $($user.Id) still has dependencies; cleanup stopped."
		[void](Invoke-MySql (
			"DELETE FROM users WHERE id=$($user.Id) AND name='$($user.Name)' " +
			"AND email='$($user.Email)'"))
		$remainingUser = @(Invoke-MySql "SELECT id FROM users WHERE id=$($user.Id)")
		Assert-Condition ($remainingUser.Count -eq 0) "Disposable user $($user.Id) remains."
	}
}

function Assert-FlywayHistory([string[]]$rows, [bool]$expectV3) {
	$expectedVersions = if ($expectV3) { '1,2,3' } else { '1,2' }
	$versions = @($rows | ForEach-Object { ($_ -split "`t")[0] })
	Assert-Condition (($versions -join ',') -eq $expectedVersions) `
		'Flyway history contains unexpected versions.'
	foreach ($row in $rows) {
		$fields = $row -split "`t"
		Assert-Condition ($fields.Count -ge 3 -and $fields[1] -eq '1') `
			'Flyway history contains an unsuccessful migration.'
	}
}

try {
	Set-Stage 'checking local prerequisites'
	Assert-Condition (Test-Path -LiteralPath $javaExecutable) 'Portable Java 21 is unavailable.'
	Assert-Condition (Test-Path -LiteralPath $jarPath) 'Packaged backend JAR is unavailable.'
	Assert-Condition (Test-Path -LiteralPath $mysqlExecutable) 'Portable MySQL 8.4.13 client is unavailable.'
	Assert-Condition (Test-Path -LiteralPath $environmentFile) 'Ignored backend/.env.local is unavailable.'
	$javaVersion = @(& $javaExecutable --version 2>&1)
	Assert-Condition ($LASTEXITCODE -eq 0 -and ($javaVersion -join "`n") -match '21\.') `
		'Portable Java 21 preflight failed.'
	$mysqlVersion = @(& $mysqlExecutable --version 2>&1)
	Assert-Condition ($LASTEXITCODE -eq 0 -and ($mysqlVersion -join "`n") -match '8\.4\.13') `
		'Portable MySQL client preflight failed.'

	foreach ($line in Get-Content -LiteralPath $environmentFile) {
		if ($line -match '^\s*([^#=]+)=(.*)$') {
			[Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
		}
	}
	Assert-Condition (-not [string]::IsNullOrWhiteSpace($env:DB_USERNAME)) 'DB_USERNAME is missing.'
	Assert-Condition (-not [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) 'DB_PASSWORD is missing.'
	Assert-Condition (-not [string]::IsNullOrWhiteSpace($env:JWT_SECRET)) 'JWT_SECRET is missing.'
	Assert-Condition ($env:DB_URL -match '^jdbc:mysql://(127\.0\.0\.1|localhost):3306/splitsmart_dev(?:\?|$)') `
		'DB_URL must target the local splitsmart_dev database.'
	$script:databaseUser = $env:DB_USERNAME
	$env:JAVA_HOME = $javaHome
	$env:PATH = (Join-Path $javaHome 'bin') + ';' + $env:PATH
	$env:MYSQL_PWD = $env:DB_PASSWORD

	$listeners = @(Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction Stop)
	Assert-Condition ($listeners.Count -eq 1 -and $listeners[0].LocalAddress -eq '127.0.0.1') `
		'Expected one MySQL listener bound to loopback.'
	$mysqlProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($listeners[0].OwningProcess)"
	Assert-Condition ($mysqlProcess.ExecutablePath -ieq $mysqlServerExecutable -and
		$mysqlProcess.CommandLine.Contains($mysqlConfiguration)) `
		'The listener is not the configured local SplitSmart MySQL instance.'
	$databaseAvailable = $true
	$databaseIdentity = @(Invoke-MySql 'SELECT CONCAT(DATABASE(),CHAR(9),@@port,CHAR(9),VERSION())')
	Assert-Condition ($databaseIdentity.Count -eq 1) 'Could not verify local database identity.'
	$identity = $databaseIdentity[0] -split "`t"
	Assert-Condition ($identity[0] -eq 'splitsmart_dev' -and $identity[1] -eq '3306' -and
		$identity[2] -match '^8\.4\.13') 'Connection did not reach the configured MySQL 8.4.13 database.'
	$apiListeners = @(Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue)
	Assert-Condition ($apiListeners.Count -eq 0) 'Port 8081 is occupied; no existing process was changed.'

	$flywayBefore = @(Invoke-MySql (
		'SELECT CONCAT(version,CHAR(9),success,CHAR(9),checksum) ' +
		'FROM flyway_schema_history ORDER BY installed_rank'))
	Assert-Condition ($flywayBefore.Count -in @(2, 3)) `
		'Expected existing Flyway V1/V2 history, optionally with V3.'
	Assert-FlywayHistory $flywayBefore ($flywayBefore.Count -eq 3)
	$oldChecksums = @($flywayBefore | Select-Object -First 2)
	$existingUsers = @(Invoke-MySql (
		"SELECT id FROM users WHERE email LIKE 'step15-$runId-%@example.test'"))
	Assert-Condition ($existingUsers.Count -eq 0) 'The unique verification user identities already exist.'
	$existingGroups = @(Invoke-MySql (
		"SELECT id FROM expense_groups WHERE name='$groupName'"))
	Assert-Condition ($existingGroups.Count -eq 0) 'The unique verification group identity already exists.'
	Set-Stage 'starting packaged backend'
	$backendProcess = Start-Process -FilePath $javaExecutable `
		-ArgumentList @('-jar', ('"{0}"' -f $jarPath), '--server.port=8081') `
		-WorkingDirectory $backendDirectory `
		-RedirectStandardOutput $stdoutLog `
		-RedirectStandardError $stderrLog `
		-PassThru
	$ready = $false
	for ($attempt = 0; $attempt -lt 60; $attempt++) {
		if ($backendProcess.HasExited) {
			throw 'The packaged backend exited before readiness.'
		}
		try {
			$health = Invoke-Api 'GET' '/api/health' '' $null
			if ($health.StatusCode -eq 200) {
				$ready = $true
				break
			}
		}
		catch {
			Start-Sleep -Seconds 1
		}
	}
	Assert-Condition $ready 'The packaged backend did not become ready.'
	Write-Host 'PASS: portable Java, configured MySQL, and packaged backend readiness'

	Set-Stage 'registering and authenticating disposable users'
	$password = "S15!$runId"
	foreach ($letter in @('A', 'B', 'C', 'D')) {
		$user = [pscustomobject]@{
			Id = $null
			Name = "S15-$runId-$letter"
			Email = "step15-$runId-$letter@example.test"
			Token = $null
		}
		$users += $user
		$registered = Invoke-Api 'POST' '/api/auth/register' '' @{
			name = $user.Name; email = $user.Email; password = $password
		}
		Assert-ApiStatus $registered 201 'Disposable user registration'
		$user.Id = [long](($registered.Body | ConvertFrom-Json).id)
		$login = Invoke-Api 'POST' '/api/auth/login' '' @{ email = $user.Email; password = $password }
		Assert-ApiStatus $login 200 'Disposable user login'
		$user.Token = [string](($login.Body | ConvertFrom-Json).accessToken)
	}

	Set-Stage 'creating the private verification group'
	$groupResponse = Invoke-Api 'POST' '/api/groups' $users[0].Token @{ name = $groupName }
	Assert-ApiStatus $groupResponse 201 'Verification group creation'
	$groupBody = $groupResponse.Body | ConvertFrom-Json
	$groupId = [long]$groupBody.id
	Assert-Condition ([long]$groupBody.createdByUserId -eq $users[0].Id) `
		'Verification group owner was unexpected.'
	$expectedMemberIds = @([long]$users[0].Id)
	foreach ($index in @(1, 2)) {
		$added = Invoke-Api 'POST' "/api/groups/$groupId/members" $users[0].Token @{
			email = $users[$index].Email
		}
		Assert-ApiStatus $added 201 'Verification group membership creation'
		$expectedMemberIds += [long]$users[$index].Id
	}
	$expectedMemberIds = @($expectedMemberIds | Sort-Object)

	Set-Stage 'creating the expense used to establish outstanding balances'
	$createdExpense = Invoke-Api 'POST' "/api/groups/$groupId/expenses" $users[0].Token @{
		description = 'Dinner'
		amount = [decimal]900
		paidByUserId = [long]$users[0].Id
		splitType = 'EQUAL'
		participantUserIds = @([long]$users[0].Id, [long]$users[1].Id, [long]$users[2].Id)
	}
	Assert-ApiStatus $createdExpense 201 'Disposable expense creation'
	$expenseBody = $createdExpense.Body | ConvertFrom-Json
	$expenseId = [long]$expenseBody.id
	Assert-Condition ([long]$expenseBody.paidByUserId -eq $users[0].Id -and
		[long]$expenseBody.createdByUserId -eq $users[0].Id) 'Expense payer or creator was unexpected.'
	$expenseRow = @(Invoke-MySql (
		"SELECT CONCAT(id,CHAR(9),group_id,CHAR(9),description,CHAR(9),CAST(amount AS CHAR)," +
		"CHAR(9),currency_code,CHAR(9),paid_by_user_id,CHAR(9),created_by_user_id) " +
		"FROM expenses WHERE id=$expenseId AND group_id=$groupId AND description='Dinner' " +
		"AND amount=900.00 AND currency_code='INR' AND paid_by_user_id=$($users[0].Id) " +
		"AND created_by_user_id=$($users[0].Id)"))
	Assert-Condition ($expenseRow.Count -eq 1) 'MySQL expense record did not match the API response.'
	$splitRows = @(Invoke-MySql (
		"SELECT CONCAT(user_id,CHAR(9),CAST(amount AS CHAR)) FROM expense_splits " +
		"WHERE expense_id=$expenseId ORDER BY user_id"))
	$expectedSplits = @(
		"$($users[0].Id)`t300.00", "$($users[1].Id)`t300.00", "$($users[2].Id)`t300.00"
	) | Sort-Object
	Assert-Condition ((@(Compare-Object $splitRows $expectedSplits)).Count -eq 0) `
		'MySQL expense split rows did not match the exact expected values.'
	Write-Host 'PASS: expense and exact equal shares persisted in MySQL'

	Set-Stage 'recording a partial settlement'
	$firstSpec = New-SettlementSpec $users[1] $users[0] '200.00' 'Partial payment'
	$settlementSpecs += $firstSpec
	$response = Invoke-Api 'POST' "/api/groups/$groupId/settlements" $users[1].Token @{
		fromUserId = $firstSpec.FromUserId
		toUserId = $firstSpec.ToUserId
		amount = [decimal]200
		requestId = $firstSpec.RequestId
		note = $firstSpec.Note
	}
	Assert-ApiStatus $response 201 'Partial settlement creation'
	$responseBody = $response.Body | ConvertFrom-Json
	$firstSpec.Id = [long]$responseBody.id
	$firstSpec.Created = $true
	Assert-Condition ([long]$responseBody.groupId -eq $firstSpec.GroupId -and
		[long]$responseBody.fromUserId -eq $firstSpec.FromUserId -and
		[long]$responseBody.toUserId -eq $firstSpec.ToUserId -and
		[long]$responseBody.recordedByUserId -eq $firstSpec.RecordedByUserId -and
		$responseBody.requestId -eq $firstSpec.RequestId) 'Settlement response identity was incorrect.'
	Assert-Money $responseBody.amount '200.00' 'Partial settlement amount'
	$partialBalances = Invoke-Api 'GET' "/api/groups/$groupId/balances" $users[0].Token $null
	Assert-ApiStatus $partialBalances 200 'Balances after partial settlement'
	Assert-GroupBalances $partialBalances.Body '400.00' '-100.00' '-300.00' `
		'0.00' '200.00' '0.00' '200.00' '0.00' '0.00'

	Set-Stage 'racing two final payments against one outstanding debt'
	$raceSpecs = @()
	foreach ($attempt in 1, 2) {
		$spec = New-SettlementSpec $users[1] $users[0] '100.00' "Concurrent payment $attempt"
		$settlementSpecs += $spec
		$raceSpecs += $spec
		$body = ConvertTo-Json -InputObject @{
			fromUserId = $spec.FromUserId
			toUserId = $spec.ToUserId
			amount = [decimal]100
			requestId = $spec.RequestId
			note = $spec.Note
		} -Depth 5 -Compress
		$raceJobs += Start-Job -ArgumentList @(
			"$baseUrl/api/groups/$groupId/settlements", $users[1].Token, $body
		) -ScriptBlock {
			param($uri, $token, $requestBody)
			try {
				$httpResponse = Invoke-WebRequest -Uri $uri -Method POST -UseBasicParsing `
					-ContentType 'application/json' -Headers @{ Authorization = "Bearer $token" } `
					-Body $requestBody -ErrorAction Stop
				[pscustomobject]@{
					StatusCode = [int]$httpResponse.StatusCode
					Body = [string]$httpResponse.Content
				}
			}
			catch {
				$webResponse = $_.Exception.Response
				if ($null -eq $webResponse) {
					throw 'Concurrent settlement request could not be completed.'
				}
				$reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
				try {
					$responseBody = $reader.ReadToEnd()
				}
				finally {
					$reader.Dispose()
				}
				[pscustomobject]@{
					StatusCode = [int]$webResponse.StatusCode
					Body = $responseBody
				}
			}
		}
	}
	$completedJobs = @($raceJobs | Wait-Job -Timeout 60)
	Assert-Condition ($completedJobs.Count -eq 2) 'Concurrent settlement requests did not complete in time.'
	$raceResults = @()
	foreach ($job in $raceJobs) {
		$raceResults += @(Receive-Job -Job $job)
		Remove-Job -Job $job -Force
	}
	$raceJobs = @()
	Assert-Condition ($raceResults.Count -eq 2) 'Concurrent settlement requests returned incomplete results.'
	$createdResults = @($raceResults | Where-Object { $_.StatusCode -eq 201 })
	$rejectedResults = @($raceResults | Where-Object { $_.StatusCode -eq 400 })
	Assert-Condition ($createdResults.Count -eq 1 -and $rejectedResults.Count -eq 1) `
		'Concurrent requests did not serialize to one successful and one rejected payment.'
	$createdResponse = $createdResults[0].Body | ConvertFrom-Json
	$createdSpec = @($raceSpecs |
		Where-Object { $_.RequestId -eq $createdResponse.requestId })[0]
	Assert-Condition ($null -ne $createdSpec) 'Concurrent response used an unknown request ID.'
	$createdSpec.Id = [long]$createdResponse.id
	$createdSpec.Created = $true
	Assert-Money $createdResponse.amount '100.00' 'Concurrent settlement amount'

	Set-Stage 'recording the final settlement'
	$lastSpec = New-SettlementSpec $users[2] $users[0] '300.00' $null
	$settlementSpecs += $lastSpec
	$response = Invoke-Api 'POST' "/api/groups/$groupId/settlements" $users[2].Token @{
		fromUserId = $lastSpec.FromUserId
		toUserId = $lastSpec.ToUserId
		amount = [decimal]300
		requestId = $lastSpec.RequestId
	}
	Assert-ApiStatus $response 201 'Final settlement creation'
	$responseBody = $response.Body | ConvertFrom-Json
	$lastSpec.Id = [long]$responseBody.id
	$lastSpec.Created = $true
	Assert-Condition ([long]$responseBody.recordedByUserId -eq $lastSpec.RecordedByUserId -and
		$responseBody.requestId -eq $lastSpec.RequestId) 'Final settlement response identity was incorrect.'
	Assert-Money $responseBody.amount '300.00' 'Final settlement amount'

	Set-Stage 'checking duplicate, overpayment, and authorization constraints'
	$duplicateRequest = @{
		fromUserId = [long]$users[1].Id
		toUserId = [long]$users[0].Id
		amount = [decimal]200
		requestId = $firstSpec.RequestId
	}
	$duplicate = Invoke-Api 'POST' "/api/groups/$groupId/settlements" $users[1].Token $duplicateRequest
	Assert-ApiStatus $duplicate 409 'Duplicate settlement requestId'
	$overpayment = Invoke-Api 'POST' "/api/groups/$groupId/settlements" $users[1].Token @{
		fromUserId = [long]$users[1].Id
		toUserId = [long]$users[0].Id
		amount = [decimal]0.01
		requestId = [Guid]::NewGuid().ToString()
	}
	Assert-ApiStatus $overpayment 400 'Overpayment rejection'
	$outsiderList = Invoke-Api 'GET' "/api/groups/$groupId/settlements" $users[3].Token $null
	Assert-ApiStatus $outsiderList 404 'Outsider settlement history access'
	$outsiderCreate = Invoke-Api 'POST' "/api/groups/$groupId/settlements" $users[3].Token @{
		fromUserId = [long]$users[3].Id
		toUserId = [long]$users[0].Id
		amount = [decimal]1
		requestId = [Guid]::NewGuid().ToString()
	}
	Assert-ApiStatus $outsiderCreate 404 'Outsider settlement creation'
	$unauthenticated = Invoke-Api 'GET' "/api/groups/$groupId/settlements" '' $null
	Assert-ApiStatus $unauthenticated 401 'Unauthenticated settlement history access'

	$databaseSettlements = @(Get-SettlementRows)
	$expectedSettlementRows = @($settlementSpecs |
		Where-Object { $_.Created } |
		ForEach-Object { Get-ExpectedSettlementRow $_ } | Sort-Object)
	$settlementRowDifferences = @(Compare-Object (@($databaseSettlements | Sort-Object)) $expectedSettlementRows)
	if ($settlementRowDifferences.Count -gt 0) {
		$differenceSummary = @($settlementRowDifferences |
			ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }) -join ' | '
		throw "MySQL settlement row verification found differences: $differenceSummary"
	}
	$history = Invoke-Api 'GET' "/api/groups/$groupId/settlements" $users[0].Token $null
	Assert-ApiStatus $history 200 'Settlement history retrieval'
	$historyItems = @()
	$parsedHistory = $history.Body | ConvertFrom-Json
	foreach ($item in $parsedHistory) {
		$historyItems += $item
	}
	$actualHistoryIds = @($historyItems | ForEach-Object { [long]($_.id) })
	$expectedHistoryIds = @($actualHistoryIds | Sort-Object -Descending)
	$actualHistoryRequestIds = @($historyItems |
		ForEach-Object { [string]$_.requestId } |
		Sort-Object)
	$expectedHistoryRequestIds = @($settlementSpecs |
		Where-Object { $_.Created } |
		ForEach-Object { [string]$_.RequestId } |
		Sort-Object)
	Assert-Condition ($historyItems.Count -eq 3 -and
		(@(Compare-Object $actualHistoryIds $expectedHistoryIds)).Count -eq 0 -and
		(@(Compare-Object $actualHistoryRequestIds $expectedHistoryRequestIds)).Count -eq 0) `
		'Settlement history was incomplete or not newest-first.'
	Write-Host 'PASS: exact MySQL settlements, duplicate protection, overpayment, and access controls'

	Set-Stage 'verifying balances, expense isolation, and health'
	$balances = Invoke-Api 'GET' "/api/groups/$groupId/balances" $users[0].Token $null
	Assert-ApiStatus $balances 200 'Group balance retrieval'
	Assert-GroupBalances $balances.Body '0.00' '0.00' '0.00' `
		'0.00' '300.00' '300.00' '600.00' '0.00' '0.00'
	$expenseList = Invoke-Api 'GET' "/api/groups/$groupId/expenses" $users[0].Token $null
	Assert-ApiStatus $expenseList 200 'Group expense listing'
	$expenseItems = @()
	$parsedExpenses = $expenseList.Body | ConvertFrom-Json
	foreach ($item in $parsedExpenses) {
		$expenseItems += $item
	}
	Assert-Condition ($expenseItems.Count -eq 1 -and [long]$expenseItems[0].id -eq $expenseId) `
		'Settlement changed or omitted the original expense.'
	Assert-Money $expenseItems[0].amount '900.00' 'Expense amount after settlements'
	$health = Invoke-Api 'GET' '/api/health' '' $null
	Assert-ApiStatus $health 200 'Health endpoint'
	Write-Host 'PASS: balances include settlements without changing expenses; health endpoint remains ready'

	Set-Stage 'verifying Flyway migration history'
	$flywayAfter = @(Invoke-MySql (
		'SELECT CONCAT(version,CHAR(9),success,CHAR(9),checksum) ' +
		'FROM flyway_schema_history ORDER BY installed_rank'))
	Assert-FlywayHistory $flywayAfter $true
	for ($index = 0; $index -lt 2; $index++) {
		Assert-Condition ($flywayAfter[$index] -eq $oldChecksums[$index]) `
			'Flyway V1/V2 history changed during startup.'
	}
	Assert-Condition (@(Invoke-MySql 'SHOW TABLES LIKE ''settlements''').Count -eq 1) `
		'The settlements table is missing after V3.'
	$schemaConstraints = @(Invoke-MySql (
		"SELECT CONCAT(constraint_type,CHAR(9),constraint_name) " +
		"FROM information_schema.table_constraints " +
		"WHERE table_schema=DATABASE() AND table_name='settlements' ORDER BY constraint_type,constraint_name"))
	$expectedConstraints = @(
		'CHECK	chk_settlements_amount_positive',
		'CHECK	chk_settlements_distinct_users',
		'FOREIGN KEY	fk_settlements_from_user',
		'FOREIGN KEY	fk_settlements_group',
		'FOREIGN KEY	fk_settlements_recorded_by_user',
		'FOREIGN KEY	fk_settlements_to_user',
		'PRIMARY KEY	PRIMARY',
		'UNIQUE	uk_settlements_group_request'
	) | Sort-Object
	Assert-Condition ((@(Compare-Object $schemaConstraints $expectedConstraints)).Count -eq 0) `
		'Settlement table constraints did not match migration V3.'
	Write-Host 'PASS: Flyway V3 is successful; V1/V2 history is unchanged'
}
catch {
	$detail = if ($_.Exception -is [System.Management.Automation.RuntimeException]) {
		$_.Exception.Message
	}
	else {
		$_.Exception.GetType().Name
	}
	$lineNumber = $_.InvocationInfo.ScriptLineNumber
	$failure = "FAIL: verification stopped during '$scenarioStage' at line $lineNumber ($($_.Exception.GetType().Name)): $detail"
	Write-Host $failure
}
finally {
	foreach ($job in $raceJobs) {
		if ($job.State -eq 'Running' -or $job.State -eq 'NotStarted') {
			Stop-Job -Job $job -ErrorAction SilentlyContinue
		}
		Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
	}
	if ($databaseAvailable -and ($users.Count -gt 0 -or $null -ne $groupId)) {
		Set-Stage 'cleaning exact disposable records'
		try {
			Remove-DisposableRecords
			Write-Host 'PASS: exact Step 15 disposable database records removed and verified absent'
		}
		catch {
			$detail = if ($_.Exception -is [System.Management.Automation.RuntimeException]) {
				$_.Exception.Message
			}
			else {
				$_.Exception.GetType().Name
			}
			$cleanupFailure = "FAIL: cleanup stopped during '$scenarioStage': $detail"
			Write-Host $cleanupFailure
			foreach ($user in $users) {
				if ($null -ne $user.Id) {
					Write-Host "Remaining-record check required for disposable user ID $($user.Id), email $($user.Email)."
				}
				else {
					Write-Host "Remaining-record check required for disposable identity $($user.Email)."
				}
			}
			if ($null -ne $groupId) {
				Write-Host "Remaining-record check required for verification group ID $groupId ($groupName)."
			}
			foreach ($settlement in $settlementSpecs) {
				if ($null -ne $settlement.Id) {
					Write-Host "Remaining-record check required for settlement ID $($settlement.Id), request ID $($settlement.RequestId)."
				}
			}
		}
	}
	if ($null -ne $backendProcess) {
		try {
			$processIdentity = Get-CimInstance Win32_Process -Filter "ProcessId=$($backendProcess.Id)"
			if ($null -ne $processIdentity -and
				$processIdentity.ExecutablePath -ieq $javaExecutable -and
				$processIdentity.CommandLine.Contains($jarPath) -and
				$processIdentity.CommandLine.Contains('--server.port=8081')) {
				Stop-Process -Id $backendProcess.Id -ErrorAction Stop
				$backendProcess.WaitForExit()
			}
			elseif ($null -ne $processIdentity) {
				$cleanupFailure = 'FAIL: backend process identity changed; the running process was not stopped.'
				Write-Host $cleanupFailure
			}
		}
		catch {
			if ($null -eq $cleanupFailure) {
				$cleanupFailure = 'FAIL: the verification-started backend process could not be safely stopped.'
			}
			Write-Host $cleanupFailure
		}
	}
	foreach ($name in $environmentNames) {
		[Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process')
	}
	foreach ($log in @($stdoutLog, $stderrLog)) {
		if (Test-Path -LiteralPath $log) {
			try {
				Remove-Item -LiteralPath $log -Force -ErrorAction Stop
			}
			catch {
				$cleanupFailure = 'FAIL: a temporary verification log could not be removed.'
				Write-Host $cleanupFailure
			}
		}
	}
}

if ($null -ne $failure -or $null -ne $cleanupFailure) {
	exit 1
}
Write-Host 'PASS: Step 15 local MySQL verification completed.'
