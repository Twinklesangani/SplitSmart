$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendDirectory = Join-Path $repositoryRoot 'backend'
$environmentFile = Join-Path $backendDirectory '.env.local'
$javaHome = Join-Path $HOME '.jdks\temurin-21'
$javaExecutable = Join-Path $javaHome 'bin\java.exe'
$jarPath = Join-Path $backendDirectory 'target\splitsmart-backend-0.0.1-SNAPSHOT.jar'
$mysqlExecutable = Join-Path $HOME 'mysql\mysql-8.4.13\bin\mysql.exe'
$mysqlServerExecutable = Join-Path $HOME 'mysql\mysql-8.4.13\bin\mysqld.exe'
$mysqlConfiguration = Join-Path $HOME 'mysql\splitsmart\my.ini'
$baseUrl = 'http://127.0.0.1:8081'
$runId = [Guid]::NewGuid().ToString('N')
$groupName = "Step14-$runId"
$scenarioStage = 'initializing'
$originalEnvironment = @{}
$environmentNames = @(
	'JAVA_HOME', 'PATH', 'MYSQL_PWD', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'JWT_SECRET'
)
foreach ($name in $environmentNames) {
	$originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$users = @()
$groupId = $null
$expectedMemberIds = @()
$expenseSpecs = @()
$backendProcess = $null
$stdoutLog = Join-Path $env:TEMP "splitsmart-step14-$runId.out.log"
$stderrLog = Join-Path $env:TEMP "splitsmart-step14-$runId.err.log"
$failureMessage = $null
$cleanupMessage = $null
$httpStatus = $null
$httpBody = $null

function Set-Stage([string]$stage) {
	$script:scenarioStage = $stage
}

function Assert-Condition([bool]$condition, [string]$message) {
	if (-not $condition) {
		throw $message
	}
}

function Invoke-MySql([string]$statement) {
	$rows = @(& $script:mysqlExecutable `
		--no-defaults `
		--protocol=TCP `
		--host=127.0.0.1 `
		--port=3306 `
		"--user=$($script:databaseUser)" `
		--database=splitsmart_dev `
		--batch `
		--raw `
		--skip-column-names `
		--execute $statement 2>$null)
	if ($LASTEXITCODE -ne 0) {
		throw "MySQL query failed during stage '$($script:scenarioStage)'."
	}
	return $rows
}

function Invoke-Api(
	[string]$method,
	[string]$path,
	[string]$token,
	[object]$body
) {
	$headers = @{ Accept = 'application/json' }
    if ($Method -eq 'POST' -and $Path -match '^/api/groups/[0-9]+/expenses$') {
        $headers['Idempotency-Key'] = '"' + [Guid]::NewGuid().ToString() + '"'
    }
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
			throw
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

function Assert-ApiStatus(
	[object]$response,
	[int]$expectedStatus,
	[string]$operation
) {
	Assert-Condition ($response.StatusCode -eq $expectedStatus) `
		"$operation returned HTTP $($response.StatusCode), expected $expectedStatus."
}

function Read-Money([object]$value) {
	return [decimal]::Parse(
		[Convert]::ToString($value, [Globalization.CultureInfo]::InvariantCulture),
		[Globalization.NumberStyles]::Number,
		[Globalization.CultureInfo]::InvariantCulture)
}

function Assert-Money([object]$actual, [string]$expected, [string]$label) {
	$expectedAmount = [decimal]::Parse(
		$expected,
		[Globalization.NumberStyles]::Number,
		[Globalization.CultureInfo]::InvariantCulture)
	Assert-Condition ((Read-Money $actual) -eq $expectedAmount) `
		"$label did not match the expected exact amount."
}

function Assert-Balances(
	[string]$json,
	[string]$expectedTotal,
	[object[]]$expectedBalances
) {
	$result = $json | ConvertFrom-Json
	Assert-Condition ($result.currencyCode -eq 'INR') 'Balance currency was not INR.'
	Assert-Money $result.totalExpenseAmount $expectedTotal 'Group expense total'
	Assert-Condition (@($result.balances).Count -eq $expectedBalances.Count) `
		'Balance response did not contain every expected group member.'

	$paidTotal = [decimal]::Zero
	$shareTotal = [decimal]::Zero
	$netTotal = [decimal]::Zero
	for ($index = 0; $index -lt $expectedBalances.Count; $index++) {
		$actual = $result.balances[$index]
		$expected = $expectedBalances[$index]
		Assert-Condition ([long]$actual.userId -eq [long]$expected.UserId) `
			'Balance members were not returned in expected user ID order.'
		Assert-Money $actual.totalPaid $expected.Paid "User $($expected.UserId) totalPaid"
		Assert-Money $actual.totalShare $expected.Share "User $($expected.UserId) totalShare"
		Assert-Money $actual.netBalance $expected.Net "User $($expected.UserId) netBalance"
		$paidTotal += Read-Money $actual.totalPaid
		$shareTotal += Read-Money $actual.totalShare
		$netTotal += Read-Money $actual.netBalance
	}

	$expectedGroupTotal = [decimal]::Parse(
		$expectedTotal,
		[Globalization.NumberStyles]::Number,
		[Globalization.CultureInfo]::InvariantCulture)
	Assert-Condition ($paidTotal -eq $expectedGroupTotal) 'Member paid totals did not equal the group total.'
	Assert-Condition ($shareTotal -eq $expectedGroupTotal) 'Member shares did not equal the group total.'
	Assert-Condition ($netTotal -eq [decimal]::Zero) 'Member net balances did not sum to zero.'
}

function ConvertTo-ExpectedExpenseRow([object]$expense) {
	return "{0}`t{1}`t{2}`t{3}`tINR`t{4}`t{5}" -f `
		$expense.Id, $script:groupId, $expense.Description, $expense.Amount,
		$expense.PayerId, $script:users[0].Id
}

function Assert-DisposableRowsBeforeCleanup {
	foreach ($user in $script:users) {
		if ($null -eq $user.Id) {
			$identityQuery = (
				"SELECT CONCAT(id,CHAR(9),name,CHAR(9),email) FROM users " +
				"WHERE name='$($user.Name)' AND email='$($user.Email)'")
		}
		else {
			$identityQuery = (
				"SELECT CONCAT(id,CHAR(9),name,CHAR(9),email) FROM users " +
				"WHERE id=$($user.Id) AND name='$($user.Name)' AND email='$($user.Email)'")
		}
		$matchingUsers = @(Invoke-MySql $identityQuery)
		if ($matchingUsers.Count -gt 1) {
			throw 'A disposable user identity matched multiple rows; cleanup stopped.'
		}
		if ($matchingUsers.Count -eq 1) {
			$fields = $matchingUsers[0] -split "`t"
			$user.Id = [long]$fields[0]
		}
		elseif ($null -ne $user.Id) {
			throw 'A tracked disposable user identity no longer matches; cleanup stopped.'
		}
	}

	if ($null -eq $script:groupId -and @($script:users).Count -gt 0 -and
		$null -ne $script:users[0].Id) {
		$foundGroups = @(Invoke-MySql (
			"SELECT id FROM expense_groups WHERE name='$($script:groupName)' " +
			"AND created_by=$($script:users[0].Id)"))
		if ($foundGroups.Count -gt 1) {
			throw 'The disposable group identity matched multiple rows; cleanup stopped.'
		}
		if ($foundGroups.Count -eq 1) {
			$script:groupId = [long]$foundGroups[0]
		}
	}

	if ($null -ne $script:groupId) {
		Assert-Condition (@($script:users).Count -ge 3) `
			'A group exists without all expected disposable users; cleanup stopped.'
		$groupRows = @(Invoke-MySql (
			"SELECT CONCAT(id,CHAR(9),name,CHAR(9),created_by) FROM expense_groups " +
			"WHERE id=$($script:groupId) AND name='$($script:groupName)' " +
			"AND created_by=$($script:users[0].Id)"))
		if ($groupRows.Count -ne 1) {
			throw 'Disposable group identity did not match; cleanup stopped.'
		}

		$script:expectedMemberIds = @(
			$script:users[0].Id,
			$script:users[1].Id,
			$script:users[2].Id
		)
		$actualMembers = @(Invoke-MySql (
			"SELECT user_id FROM group_members WHERE group_id=$($script:groupId) ORDER BY user_id"))
		$allowedMemberIds = @($script:expectedMemberIds | ForEach-Object { [string]$_ } | Sort-Object -Unique)
		foreach ($memberId in $actualMembers) {
			Assert-Condition ($allowedMemberIds -contains [string]$memberId) `
				'An unexpected user is a member of the disposable group; cleanup stopped.'
		}
		Assert-Condition ($actualMembers -contains [string]$script:users[0].Id) `
			'The disposable creator membership is missing; cleanup stopped.'

		foreach ($expense in $script:expenseSpecs) {
			if ($null -eq $expense.Id -and -not $expense.Deleted) {
				$matchingExpenses = @(Invoke-MySql (
					"SELECT id FROM expenses WHERE group_id=$($script:groupId) " +
					"AND description='$($expense.Description)' AND amount=$($expense.Amount) " +
					"AND paid_by_user_id=$($expense.PayerId) " +
					"AND created_by_user_id=$($script:users[0].Id)"))
				if ($matchingExpenses.Count -gt 1) {
					throw 'A disposable expense identity matched multiple rows; cleanup stopped.'
				}
				if ($matchingExpenses.Count -eq 1) {
					$expense.Id = [long]$matchingExpenses[0]
				}
			}
		}

		$expenseRows = @(Invoke-MySql (
			"SELECT CONCAT(id,CHAR(9),group_id,CHAR(9),description,CHAR(9),CAST(amount AS CHAR)," +
			"CHAR(9),currency_code,CHAR(9),paid_by_user_id,CHAR(9),created_by_user_id) " +
			"FROM expenses WHERE group_id=$($script:groupId) ORDER BY id"))
		$expectedExpenses = @($script:expenseSpecs |
			Where-Object { -not $_.Deleted } |
			ForEach-Object { ConvertTo-ExpectedExpenseRow $_ })
		if ((Compare-Object $expenseRows $expectedExpenses)) {
			throw 'Disposable expense identities differ from the tracked records; cleanup stopped.'
		}

		$expenseIds = @($script:expenseSpecs |
			Where-Object { $null -ne $_.Id } |
			ForEach-Object { [string]$_.Id })
		$expectedSplits = @($script:expenseSpecs |
			Where-Object { -not $_.Deleted } |
			ForEach-Object {
				$expense = $_
				foreach ($split in $expense.Splits) {
					"{0}`t{1}`t{2}" -f $expense.Id, $split.UserId, $split.Amount
				}
			} | Sort-Object)
		if ($expenseIds.Count -gt 0) {
			$idList = $expenseIds -join ','
			$actualSplits = @(Invoke-MySql (
				"SELECT CONCAT(expense_id,CHAR(9),user_id,CHAR(9),CAST(amount AS CHAR)) " +
				"FROM expense_splits WHERE expense_id IN ($idList) ORDER BY expense_id,user_id"))
		}
		else {
			$actualSplits = @()
		}
		if ((Compare-Object $actualSplits $expectedSplits)) {
			throw 'Disposable split identities differ from the tracked records; cleanup stopped.'
		}
	}

	$expenseIds = @($script:expenseSpecs |
		Where-Object { $null -ne $_.Id } |
		ForEach-Object { [long]$_.Id })
	foreach ($user in $script:users) {
		if ($null -eq $user.Id) {
			continue
		}
		if ($null -eq $script:groupId) {
			$otherMemberships = @(Invoke-MySql (
				"SELECT id FROM group_members WHERE user_id=$($user.Id)"))
			$otherGroups = @(Invoke-MySql (
				"SELECT id FROM expense_groups WHERE created_by=$($user.Id)"))
		}
		else {
			$otherMemberships = @(Invoke-MySql (
				"SELECT id FROM group_members WHERE user_id=$($user.Id) " +
				"AND group_id<>$($script:groupId)"))
			$otherGroups = @(Invoke-MySql (
				"SELECT id FROM expense_groups WHERE created_by=$($user.Id) " +
				"AND id<>$($script:groupId)"))
		}
		if ($expenseIds.Count -gt 0) {
			$notTrackedExpenseIds = $expenseIds -join ','
			$otherExpenses = @(Invoke-MySql (
				"SELECT id FROM expenses WHERE (paid_by_user_id=$($user.Id) " +
				"OR created_by_user_id=$($user.Id)) AND id NOT IN ($notTrackedExpenseIds)"))
			$otherSplits = @(Invoke-MySql (
				"SELECT id FROM expense_splits WHERE user_id=$($user.Id) " +
				"AND expense_id NOT IN ($notTrackedExpenseIds)"))
		}
		else {
			$otherExpenses = @(Invoke-MySql (
				"SELECT id FROM expenses WHERE paid_by_user_id=$($user.Id) " +
				"OR created_by_user_id=$($user.Id)"))
			$otherSplits = @(Invoke-MySql "SELECT id FROM expense_splits WHERE user_id=$($user.Id)")
		}
		Assert-Condition (
			$otherMemberships.Count -eq 0 -and $otherGroups.Count -eq 0 -and
			$otherExpenses.Count -eq 0 -and $otherSplits.Count -eq 0) `
			"User $($user.Id) has dependencies outside the verification records; cleanup stopped."
	}
}

function Remove-DisposableRecords {
	Assert-DisposableRowsBeforeCleanup

	$expenseIds = @($script:expenseSpecs |
		Where-Object { $null -ne $_.Id } |
		ForEach-Object { [long]$_.Id })
	if ($expenseIds.Count -gt 0) {
		$idList = $expenseIds -join ','
		[void](Invoke-MySql "DELETE FROM expense_splits WHERE expense_id IN ($idList)")
		$remainingSplits = @(Invoke-MySql (
			"SELECT id FROM expense_splits WHERE expense_id IN ($idList)"))
		Assert-Condition ($remainingSplits.Count -eq 0) 'Disposable splits remain after cleanup.'

		foreach ($expense in $script:expenseSpecs) {
			if ($null -eq $expense.Id) {
				continue
			}
			$deleteExpense = @(
				"DELETE FROM expenses WHERE id=$($expense.Id) AND group_id=$($script:groupId)",
				"AND description='$($expense.Description)' AND amount=$($expense.Amount)",
				"AND currency_code='INR' AND paid_by_user_id=$($expense.PayerId)",
				"AND created_by_user_id=$($script:users[0].Id)"
			) -join ' '
			[void](Invoke-MySql $deleteExpense)
		}
		$remainingExpenses = @(Invoke-MySql (
			"SELECT id FROM expenses WHERE group_id=$($script:groupId)"))
		Assert-Condition ($remainingExpenses.Count -eq 0) 'Disposable expenses remain after cleanup.'
	}

	if ($null -ne $script:groupId) {
		$memberIds = @($script:expectedMemberIds | Sort-Object -Unique)
		if ($memberIds.Count -gt 0) {
			$memberList = $memberIds -join ','
			[void](Invoke-MySql (
				"DELETE FROM group_members WHERE group_id=$($script:groupId) " +
				"AND user_id IN ($memberList)"))
		}
		$remainingMembers = @(Invoke-MySql (
			"SELECT id FROM group_members WHERE group_id=$($script:groupId)"))
		Assert-Condition ($remainingMembers.Count -eq 0) 'Disposable memberships remain after cleanup.'

		[void](Invoke-MySql (
			"DELETE FROM expense_groups WHERE id=$($script:groupId) " +
			"AND name='$($script:groupName)' AND created_by=$($script:users[0].Id)"))
		$remainingGroup = @(Invoke-MySql (
			"SELECT id FROM expense_groups WHERE id=$($script:groupId)"))
		Assert-Condition ($remainingGroup.Count -eq 0) 'Disposable group remains after cleanup.'
	}

	foreach ($user in $script:users) {
		if ($null -eq $user.Id) {
			continue
		}
		$remainingDependencies = @(Invoke-MySql (
			"SELECT (SELECT COUNT(*) FROM group_members WHERE user_id=$($user.Id))+" +
			"(SELECT COUNT(*) FROM expense_groups WHERE created_by=$($user.Id))+" +
			"(SELECT COUNT(*) FROM expenses WHERE paid_by_user_id=$($user.Id) " +
			"OR created_by_user_id=$($user.Id))+" +
			"(SELECT COUNT(*) FROM expense_splits WHERE user_id=$($user.Id))"))
		Assert-Condition ($remainingDependencies.Count -eq 1 -and $remainingDependencies[0] -eq '0') `
			"User $($user.Id) still has dependencies; cleanup stopped."
		[void](Invoke-MySql (
			"DELETE FROM users WHERE id=$($user.Id) AND name='$($user.Name)' " +
			"AND email='$($user.Email)'"))
		$remainingUser = @(Invoke-MySql "SELECT id FROM users WHERE id=$($user.Id)")
		Assert-Condition ($remainingUser.Count -eq 0) "Disposable user $($user.Id) remains after cleanup."
	}

	Write-Host 'PASS: exact Step 14 disposable records cleaned and verified absent'
}

try {
	Set-Stage 'checking local prerequisites'
	Assert-Condition (Test-Path -LiteralPath $javaExecutable) 'Portable Java 21 is unavailable.'
	Assert-Condition (Test-Path -LiteralPath $jarPath) 'Packaged backend JAR is unavailable.'
	Assert-Condition (Test-Path -LiteralPath $mysqlExecutable) 'Portable MySQL client is unavailable.'
	Assert-Condition (Test-Path -LiteralPath $environmentFile) 'Ignored backend/.env.local is unavailable.'

	$javaOutput = @(& $javaExecutable --version 2>&1)
	Assert-Condition ($LASTEXITCODE -eq 0 -and ($javaOutput -join "`n") -match '21\.') `
		'Portable Java 21 preflight failed.'
	$mysqlVersionOutput = @(& $mysqlExecutable --version 2>&1)
	Assert-Condition ($LASTEXITCODE -eq 0 -and ($mysqlVersionOutput -join "`n") -match '8\.4\.13') `
		'Portable MySQL 8.4.13 client preflight failed.'
	foreach ($line in Get-Content -LiteralPath $environmentFile) {
		if ($line -match '^\s*([^#=]+)=(.*)$') {
			$name = $matches[1].Trim()
			$value = $matches[2].Trim()
			[Environment]::SetEnvironmentVariable($name, $value, 'Process')
		}
	}
	Assert-Condition (-not [string]::IsNullOrWhiteSpace($env:DB_USERNAME)) `
		'DB_USERNAME is missing from the ignored local environment file.'
	Assert-Condition (-not [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) `
		'DB_PASSWORD is missing from the ignored local environment file.'
	Assert-Condition (-not [string]::IsNullOrWhiteSpace($env:JWT_SECRET)) `
		'JWT_SECRET is missing from the ignored local environment file.'
	Assert-Condition ($env:DB_URL -match '^jdbc:mysql://(127\.0\.0\.1|localhost):3306/splitsmart_dev(?:\?|$)') `
		'DB_URL must target splitsmart_dev on local port 3306.'
	$databaseUser = $env:DB_USERNAME
	$env:JAVA_HOME = $javaHome
	$env:PATH = (Join-Path $javaHome 'bin') + ';' + $env:PATH
	$env:MYSQL_PWD = $env:DB_PASSWORD

	$mysqlListeners = @(Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction Stop)
	Assert-Condition ($mysqlListeners.Count -eq 1 -and $mysqlListeners[0].LocalAddress -eq '127.0.0.1') `
		'Expected exactly one loopback MySQL listener.'
	$mysqlProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($mysqlListeners[0].OwningProcess)"
	Assert-Condition ($mysqlProcess.ExecutablePath -ieq $mysqlServerExecutable) `
		'Port 3306 is not owned by the configured portable MySQL instance.'
	Assert-Condition ($mysqlProcess.CommandLine.Contains($mysqlConfiguration)) `
		'The active MySQL process is not using the configured SplitSmart data configuration.'
	$databaseIdentity = @(Invoke-MySql 'SELECT CONCAT(DATABASE(),CHAR(9),@@port,CHAR(9),VERSION())')
	Assert-Condition ($databaseIdentity.Count -eq 1) 'The local MySQL database identity could not be verified.'
	$databaseIdentityFields = $databaseIdentity[0] -split "`t"
	Assert-Condition ($databaseIdentityFields[0] -eq 'splitsmart_dev' -and
		$databaseIdentityFields[1] -eq '3306' -and $databaseIdentityFields[2] -match '^8\.4\.13') `
		'The connection is not to splitsmart_dev on the expected MySQL 8.4.13 instance.'
	$existingBackendListeners = @(Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue)
	Assert-Condition ($existingBackendListeners.Count -eq 0) `
		'Port 8081 is already occupied; no existing process was changed.'

	$flywayBefore = @(Invoke-MySql (
		"SELECT CONCAT(version,CHAR(9),success,CHAR(9),checksum) " +
		"FROM flyway_schema_history ORDER BY installed_rank"))
	Assert-Condition ($flywayBefore.Count -eq 2) 'Expected only Flyway V1 and V2 before startup.'
	$flywayVersions = @($flywayBefore | ForEach-Object { ($_ -split "`t")[0] })
	Assert-Condition (($flywayVersions -join ',') -eq '1,2') `
		'Flyway history must contain V1 and V2 only.'
	foreach ($migration in $flywayBefore) {
		Assert-Condition (($migration -split "`t")[1] -eq '1') `
			'Flyway history contains an unsuccessful migration.'
	}
	$existingRunRecords = @(Invoke-MySql (
		"SELECT id FROM users WHERE email LIKE 'step14-$runId-%@example.test'"))
	Assert-Condition ($existingRunRecords.Count -eq 0) 'The unique verification email tag already exists.'
	$existingRunGroups = @(Invoke-MySql (
		"SELECT id FROM expense_groups WHERE name='$groupName'"))
	Assert-Condition ($existingRunGroups.Count -eq 0) 'The unique verification group tag already exists.'
	$existingRunExpenses = @(Invoke-MySql (
		"SELECT id FROM expenses WHERE description LIKE 'Step14-$runId-%'"))
	Assert-Condition ($existingRunExpenses.Count -eq 0) 'The unique verification expense tag already exists.'

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
	Assert-Condition $ready 'The packaged backend did not become ready on port 8081.'
	Write-Host 'PASS: portable Java 21, configured MySQL, migrations, and packaged backend startup'

	Set-Stage 'registering and authenticating disposable users'
	$password = "S14!$runId"
	$userLetters = @('A', 'B', 'C', 'D')
	foreach ($letter in $userLetters) {
		$user = [pscustomobject]@{
			Id = $null
			Name = "S14-$runId-$letter"
			Email = "step14-$runId-$letter@example.test"
			Token = $null
		}
		$script:users += $user
		$registered = Invoke-Api 'POST' '/api/auth/register' '' @{
			name = $user.Name
			email = $user.Email
			password = $password
		}
		Assert-ApiStatus $registered 201 'User registration'
		$registeredBody = $registered.Body | ConvertFrom-Json
		$user.Id = [long]$registeredBody.id
		$login = Invoke-Api 'POST' '/api/auth/login' '' @{
			email = $user.Email
			password = $password
		}
		Assert-ApiStatus $login 200 'User login'
		$user.Token = [string](($login.Body | ConvertFrom-Json).accessToken)
	}

	Set-Stage 'creating private group and memberships'
	$groupResponse = Invoke-Api 'POST' '/api/groups' $users[0].Token @{ name = $groupName }
	Assert-ApiStatus $groupResponse 201 'Group creation'
	$groupBody = $groupResponse.Body | ConvertFrom-Json
	$groupId = [long]$groupBody.id
	Assert-Condition ([long]$groupBody.createdByUserId -eq $users[0].Id) `
		'The verification group creator ID was unexpected.'
	$expectedMemberIds = @([long]$users[0].Id)
	foreach ($memberIndex in @(1, 2)) {
		$addedMember = Invoke-Api 'POST' "/api/groups/$groupId/members" $users[0].Token @{
			email = $users[$memberIndex].Email
		}
		Assert-ApiStatus $addedMember 201 'Group membership creation'
		$expectedMemberIds += [long]$users[$memberIndex].Id
	}

	Set-Stage 'checking empty-group balances'
	$emptyBalances = Invoke-Api 'GET' "/api/groups/$groupId/balances" $users[0].Token $null
	Assert-ApiStatus $emptyBalances 200 'Empty-group balance retrieval'
	Assert-Balances $emptyBalances.Body '0.00' @(
		@{ UserId = $users[0].Id; Paid = '0.00'; Share = '0.00'; Net = '0.00' },
		@{ UserId = $users[1].Id; Paid = '0.00'; Share = '0.00'; Net = '0.00' },
		@{ UserId = $users[2].Id; Paid = '0.00'; Share = '0.00'; Net = '0.00' })

	Set-Stage 'creating two expenses and verifying balances'
	$expense1Description = "Step14-$runId-E1"
	$expense1 = [pscustomobject]@{
		Id = $null; Description = $expense1Description; Amount = '900.00'
		PayerId = [long]$users[0].Id; Deleted = $false
		Splits = @(
			@{ UserId = [long]$users[0].Id; Amount = '300.00' },
			@{ UserId = [long]$users[1].Id; Amount = '300.00' },
			@{ UserId = [long]$users[2].Id; Amount = '300.00' })
	}
	$script:expenseSpecs += $expense1
	$createdExpense1 = Invoke-Api 'POST' "/api/groups/$groupId/expenses" $users[0].Token @{
		description = $expense1Description
		amount = [decimal]::Parse('900.00', [Globalization.CultureInfo]::InvariantCulture)
		paidByUserId = $users[0].Id
		splitType = 'EQUAL'
		participantUserIds = @($users[0].Id, $users[1].Id, $users[2].Id)
	}
	Assert-ApiStatus $createdExpense1 201 'First expense creation'
	$expense1.Id = [long](($createdExpense1.Body | ConvertFrom-Json).id)

	$expense2Description = "Step14-$runId-E2"
	$expense2 = [pscustomobject]@{
		Id = $null; Description = $expense2Description; Amount = '600.00'
		PayerId = [long]$users[1].Id; Deleted = $false
		Splits = @(
			@{ UserId = [long]$users[0].Id; Amount = '100.00' },
			@{ UserId = [long]$users[1].Id; Amount = '200.00' },
			@{ UserId = [long]$users[2].Id; Amount = '300.00' })
	}
	$script:expenseSpecs += $expense2
	$createdExpense2 = Invoke-Api 'POST' "/api/groups/$groupId/expenses" $users[0].Token @{
		description = $expense2Description
		amount = [decimal]::Parse('600.00', [Globalization.CultureInfo]::InvariantCulture)
		paidByUserId = $users[1].Id
		splits = @(
			@{ userId = $users[0].Id; amount = [decimal]::Parse('100.00', [Globalization.CultureInfo]::InvariantCulture) },
			@{ userId = $users[1].Id; amount = [decimal]::Parse('200.00', [Globalization.CultureInfo]::InvariantCulture) },
			@{ userId = $users[2].Id; amount = [decimal]::Parse('300.00', [Globalization.CultureInfo]::InvariantCulture) })
	}
	Assert-ApiStatus $createdExpense2 201 'Second expense creation'
	$expense2.Id = [long](($createdExpense2.Body | ConvertFrom-Json).id)
	$initialExpected = @(
		@{ UserId = $users[0].Id; Paid = '900.00'; Share = '400.00'; Net = '500.00' },
		@{ UserId = $users[1].Id; Paid = '600.00'; Share = '500.00'; Net = '100.00' },
		@{ UserId = $users[2].Id; Paid = '0.00'; Share = '600.00'; Net = '-600.00' })
	$initialBalances = Invoke-Api 'GET' "/api/groups/$groupId/balances" $users[0].Token $null
	Assert-ApiStatus $initialBalances 200 'Initial balance retrieval'
	Assert-Balances $initialBalances.Body '1500.00' $initialExpected
	$memberBalances = Invoke-Api 'GET' "/api/groups/$groupId/balances" $users[1].Token $null
	Assert-ApiStatus $memberBalances 200 'Member balance retrieval'
	Assert-Balances $memberBalances.Body '1500.00' $initialExpected
	$outsiderBalances = Invoke-Api 'GET' "/api/groups/$groupId/balances" $users[3].Token $null
	Assert-ApiStatus $outsiderBalances 404 'Unrelated-user balance access'
	$anonymousBalances = Invoke-Api 'GET' "/api/groups/$groupId/balances" '' $null
	Assert-ApiStatus $anonymousBalances 401 'Unauthenticated balance access'
	Write-Host 'PASS: initial exact balances, member authorization, outsider privacy, and unauthenticated rejection'

	Set-Stage 'verifying database-backed exact amounts'
	$expenseRows = @(Invoke-MySql (
		"SELECT CONCAT(id,CHAR(9),group_id,CHAR(9),description,CHAR(9),CAST(amount AS CHAR)," +
		"CHAR(9),currency_code,CHAR(9),paid_by_user_id,CHAR(9),created_by_user_id) " +
		"FROM expenses WHERE id IN ($($expense1.Id),$($expense2.Id)) ORDER BY id"))
	$expectedExpenseRows = @(
		"$($expense1.Id)`t$groupId`t$expense1Description`t900.00`tINR`t$($users[0].Id)`t$($users[0].Id)",
		"$($expense2.Id)`t$groupId`t$expense2Description`t600.00`tINR`t$($users[1].Id)`t$($users[0].Id)"
	)
	if ((Compare-Object $expenseRows $expectedExpenseRows)) {
		throw 'Persisted expense identities or exact monetary values were unexpected.'
	}
	$storedSplits = @(Invoke-MySql (
		"SELECT CONCAT(expense_id,CHAR(9),user_id,CHAR(9),CAST(amount AS CHAR)) " +
		"FROM expense_splits WHERE expense_id IN ($($expense1.Id),$($expense2.Id)) " +
		"ORDER BY expense_id,user_id"))
	$expectedStoredSplits = @(
		"$($expense1.Id)`t$($users[0].Id)`t300.00",
		"$($expense1.Id)`t$($users[1].Id)`t300.00",
		"$($expense1.Id)`t$($users[2].Id)`t300.00",
		"$($expense2.Id)`t$($users[0].Id)`t100.00",
		"$($expense2.Id)`t$($users[1].Id)`t200.00",
		"$($expense2.Id)`t$($users[2].Id)`t300.00"
	)
	if ((Compare-Object $storedSplits $expectedStoredSplits)) {
		throw 'Persisted expense shares or exact monetary values were unexpected.'
	}
	Write-Host 'PASS: MySQL expense and split rows persisted for exact totals'

	Set-Stage 'updating expense and recalculating balances'
	$updatedExpense = Invoke-Api 'PUT' "/api/groups/$groupId/expenses/$($expense2.Id)" $users[0].Token @{
		description = $expense2Description
		amount = [decimal]::Parse('300.00', [Globalization.CultureInfo]::InvariantCulture)
		paidByUserId = $users[1].Id
		splits = @(
			@{ userId = $users[0].Id; amount = [decimal]::Parse('50.00', [Globalization.CultureInfo]::InvariantCulture) },
			@{ userId = $users[1].Id; amount = [decimal]::Parse('100.00', [Globalization.CultureInfo]::InvariantCulture) },
			@{ userId = $users[2].Id; amount = [decimal]::Parse('150.00', [Globalization.CultureInfo]::InvariantCulture) })
	}
	Assert-ApiStatus $updatedExpense 200 'Expense update'
	$expense2.Amount = '300.00'
	$expense2.Splits = @(
		@{ UserId = [long]$users[0].Id; Amount = '50.00' },
		@{ UserId = [long]$users[1].Id; Amount = '100.00' },
		@{ UserId = [long]$users[2].Id; Amount = '150.00' })
	$afterUpdate = Invoke-Api 'GET' "/api/groups/$groupId/balances" $users[0].Token $null
	Assert-ApiStatus $afterUpdate 200 'Post-update balance retrieval'
	Assert-Balances $afterUpdate.Body '1200.00' @(
		@{ UserId = $users[0].Id; Paid = '900.00'; Share = '350.00'; Net = '550.00' },
		@{ UserId = $users[1].Id; Paid = '300.00'; Share = '400.00'; Net = '-100.00' },
		@{ UserId = $users[2].Id; Paid = '0.00'; Share = '450.00'; Net = '-450.00' })
	Write-Host 'PASS: expense edit changed live balances and preserved aggregate invariants'

	Set-Stage 'deleting expense and checking remaining balance'
	$deletedExpense = Invoke-Api 'DELETE' "/api/groups/$groupId/expenses/$($expense2.Id)" $users[0].Token $null
	Assert-ApiStatus $deletedExpense 204 'Expense deletion'
	$expense2.Deleted = $true
	$afterDelete = Invoke-Api 'GET' "/api/groups/$groupId/balances" $users[0].Token $null
	Assert-ApiStatus $afterDelete 200 'Post-delete balance retrieval'
	Assert-Balances $afterDelete.Body '900.00' @(
		@{ UserId = $users[0].Id; Paid = '900.00'; Share = '300.00'; Net = '600.00' },
		@{ UserId = $users[1].Id; Paid = '0.00'; Share = '300.00'; Net = '-300.00' },
		@{ UserId = $users[2].Id; Paid = '0.00'; Share = '300.00'; Net = '-300.00' })
	$health = Invoke-Api 'GET' '/api/health' '' $null
	Assert-ApiStatus $health 200 'Health endpoint'
	$flywayAfter = @(Invoke-MySql (
		"SELECT CONCAT(version,CHAR(9),success,CHAR(9),checksum) " +
		"FROM flyway_schema_history ORDER BY installed_rank"))
	if ((Compare-Object $flywayBefore $flywayAfter)) {
		throw 'Flyway history changed during verification.'
	}
	Write-Host 'PASS: delete recalculation, health endpoint, and unchanged Flyway history'
}
catch {
	$failureMessage = $_.Exception.Message
	Write-Host "FAIL: verification stage '$scenarioStage' - $failureMessage"
}
finally {
	if ($null -ne $env:DB_USERNAME) {
		try {
			Set-Stage 'cleaning uniquely identified disposable records'
			Remove-DisposableRecords
			$cleanupMessage = 'PASS'
		}
		catch {
			$cleanupMessage = $_.Exception.Message
			Write-Host "FAIL: cleanup stage - $cleanupMessage"
		}
	}

	if ($null -ne $backendProcess) {
		$ownedProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($backendProcess.Id)"
		if ($ownedProcess -and
			$ownedProcess.ExecutablePath -ieq $javaExecutable -and
			$ownedProcess.CommandLine.Contains($jarPath) -and
			$ownedProcess.CommandLine.Contains('--server.port=8081')) {
			$process = Get-Process -Id $backendProcess.Id -ErrorAction SilentlyContinue
			if ($process -and $process.CloseMainWindow()) {
				[void]$process.WaitForExit(5000)
			}
			if (-not $backendProcess.HasExited) {
				Stop-Process -Id $backendProcess.Id
				[void]$backendProcess.WaitForExit(10000)
			}
		}
		else {
			$cleanupMessage = 'Backend process identity changed; it was not stopped.'
			Write-Host "FAIL: $cleanupMessage"
		}
	}

	foreach ($name in $originalEnvironment.Keys) {
		[Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], 'Process')
	}
	foreach ($logFile in @($stdoutLog, $stderrLog)) {
		if (Test-Path -LiteralPath $logFile) {
			Remove-Item -LiteralPath $logFile -Force
		}
	}
}

if ($null -ne $failureMessage) {
	throw "Step 14 live verification failed: $failureMessage"
}
if ($cleanupMessage -ne 'PASS') {
	throw "Step 14 live verification cleanup failed: $cleanupMessage"
}
Write-Host 'PASS: Step 14 live MySQL verification completed and disposable records were removed'
