[CmdletBinding()]
param(
	[switch]$Extended,
	[switch]$Stateful,
	[int]$ApiPort = 18081
)

if ($Extended -and $Stateful) {
	throw 'Choose either -Extended or -Stateful for a single audit run.'
}

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$reportRoot = Join-Path $backendRoot 'target\reports\schemathesis'
$jarPath = Join-Path $backendRoot 'target\splitsmart-backend-0.0.1-SNAPSHOT.jar'
$schemathesisPath = Join-Path $backendRoot '.venv-schemathesis\Scripts\schemathesis.exe'
$image = 'mysql:8.4.13'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$containerName = "splitsmart-schemathesis-$runId"
$databaseName = "splitsmart_st_$runId"
$databaseUser = "st_user_$runId"
$reportPrefix = if ($Stateful) {
	'phase4c-stateful'
} elseif ($Extended) {
	'phase4c-member'
} else {
	'phase4c-initial'
}
$proxyCountPath = Join-Path $reportRoot "$reportPrefix-proxy-count-$runId.txt"
$baseUrl = "http://127.0.0.1:$ApiPort"
$schemathesisBaseUrl = $null
$apiProcess = $null
$proxyProcess = $null
$proxyPort = $null
$stdoutTask = $null
$stderrTask = $null
$proxyStderrTask = $null
$containerId = $null
$schemaOperationCount = 0
$countsBeforeFuzz = $null
$countsAfterFuzz = $null
$schemathesisVersion = $null
$cliResults = @()
$cleanupFailure = $null
$apiRequestCount = 0
$traceProbes = @()

function Get-ListenerOwners {
	param([int]$Port)

	$connections = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
	return @($connections | ForEach-Object { [string]$_.OwningProcess } | Sort-Object -Unique)
}

function New-RandomSecret {
	$bytes = New-Object byte[] 32
	$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
	try {
		$generator.GetBytes($bytes)
	} finally {
		$generator.Dispose()
	}
	return [Convert]::ToBase64String($bytes)
}

function Invoke-DockerCommand {
	param(
		[Parameter(Mandatory)][string[]]$Arguments,
		[hashtable]$Environment = @{}
	)

	$previousEnvironment = @{}
	foreach ($name in $Environment.Keys) {
		$previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
		[Environment]::SetEnvironmentVariable($name, [string]$Environment[$name], 'Process')
	}
	try {
		$output = @(& docker @Arguments 2>&1)
		$exitCode = $LASTEXITCODE
	} finally {
		foreach ($name in $Environment.Keys) {
			[Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
		}
	}
	return [PSCustomObject]@{
		ExitCode = $exitCode
		Output = $output
	}
}

function Invoke-DbQuery {
	param([string]$Sql)

	$result = Invoke-DockerCommand -Environment @{ MYSQL_PWD = $databasePassword } -Arguments @(
		'exec', '--env', 'MYSQL_PWD', $containerName, 'mysql',
		'--batch', '--skip-column-names', "--user=$databaseUser", $databaseName, "--execute=$Sql"
	)
	if ($result.ExitCode -ne 0) {
		$message = (($result.Output | ForEach-Object { [string]$_ }) -join ' ').Trim()
		if ([string]::IsNullOrWhiteSpace($message)) {
			throw 'Could not query the disposable MySQL database.'
		}
		throw "Could not query the disposable MySQL database: $message"
	}
	return (($result.Output -join "`n").Trim())
}

function Invoke-Api {
	param(
		[string]$Method,
		[string]$Path,
		[string]$Token,
		[AllowNull()][object]$Body
	)

	$headers = @{ Accept = 'application/json' }
	if (-not [string]::IsNullOrWhiteSpace($Token)) {
		$headers.Authorization = "Bearer $Token"
	}
	$request = @{
		Uri = "$baseUrl$Path"
		Method = $Method
		Headers = $headers
		TimeoutSec = 10
		UseBasicParsing = $true
	}
	if ($null -ne $Body) {
		$request.ContentType = 'application/json'
		$request.Body = ConvertTo-Json -InputObject $Body -Depth 30 -Compress
	}

	$script:apiRequestCount++
	try {
		$response = Invoke-WebRequest @request
		$status = [int]$response.StatusCode
		$raw = [string]$response.Content
	} catch {
		$webResponse = $_.Exception.Response
		if ($null -eq $webResponse) {
			throw
		}
		$status = [int]$webResponse.StatusCode
		$reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
		try {
			$raw = $reader.ReadToEnd()
		} finally {
			$reader.Dispose()
			$webResponse.Dispose()
		}
	}

	$parsed = $null
	if (-not [string]::IsNullOrWhiteSpace($raw)) {
		try {
			$parsed = ConvertFrom-Json -InputObject $raw
		} catch {
			throw "API returned invalid JSON for $Method $Path (HTTP $status)."
		}
	}
	return [PSCustomObject]@{
		StatusCode = $status
		Body = $parsed
		Raw = $raw
	}
}

function Invoke-TraceProbe {
	param(
		[string]$Path,
		[string]$Token,
		[string]$ProbeValue
	)

	$handler = [System.Net.Http.HttpClientHandler]::new()
	$handler.UseProxy = $false
	$client = [System.Net.Http.HttpClient]::new($handler)
	$requestMessage = [System.Net.Http.HttpRequestMessage]::new(
		[System.Net.Http.HttpMethod]::new('TRACE'),
		[System.Uri]::new("$baseUrl$Path"))
	try {
		$requestMessage.Headers.TryAddWithoutValidation('X-Trace-Probe', $ProbeValue) | Out-Null
		if (-not [string]::IsNullOrWhiteSpace($Token)) {
			$requestMessage.Headers.TryAddWithoutValidation('Authorization', "Bearer $Token") | Out-Null
		}
		$script:apiRequestCount++
		$response = $client.SendAsync($requestMessage).GetAwaiter().GetResult()
		try {
			$body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
			$status = [int]$response.StatusCode
			if ($status -ne 400) {
				throw "TRACE $Path expected Tomcat HTTP 400 but received HTTP $status."
			}
			if ($body.Contains($ProbeValue) -or
					(-not [string]::IsNullOrWhiteSpace($Token) -and $body.Contains($Token))) {
				throw "TRACE $Path reflected a probe header or bearer token."
			}
			if (-not [string]::IsNullOrEmpty($body)) {
				throw "TRACE $Path returned a response body instead of Tomcat's empty rejection."
			}
			return [PSCustomObject]@{
				Path = $Path
				StatusCode = $status
				ResponseBodyLength = $body.Length
				ReflectedProbe = $false
				ReflectedToken = $false
			}
		} finally {
			$response.Dispose()
		}
	} finally {
		$requestMessage.Dispose()
		$client.Dispose()
		$handler.Dispose()
	}
}

function Assert-Status {
	param($Response, [int]$Expected, [string]$Label)
	if ($Response.StatusCode -ne $Expected) {
		throw "$Label expected HTTP $Expected but received HTTP $($Response.StatusCode)."
	}
}

function Assert-SafeDatabase {
	$brokenReferences = Invoke-DbQuery @'
SELECT
  (SELECT COUNT(*) FROM expense_groups g LEFT JOIN users u ON u.id = g.created_by WHERE u.id IS NULL) +
  (SELECT COUNT(*) FROM group_members m LEFT JOIN expense_groups g ON g.id = m.group_id WHERE g.id IS NULL) +
  (SELECT COUNT(*) FROM group_members m LEFT JOIN users u ON u.id = m.user_id WHERE u.id IS NULL) +
  (SELECT COUNT(*) FROM expenses e LEFT JOIN expense_groups g ON g.id = e.group_id WHERE g.id IS NULL) +
  (SELECT COUNT(*) FROM expenses e LEFT JOIN users u ON u.id = e.paid_by_user_id WHERE u.id IS NULL) +
  (SELECT COUNT(*) FROM expenses e LEFT JOIN users u ON u.id = e.created_by_user_id WHERE u.id IS NULL) +
  (SELECT COUNT(*) FROM expense_splits s LEFT JOIN expenses e ON e.id = s.expense_id WHERE e.id IS NULL) +
  (SELECT COUNT(*) FROM expense_splits s LEFT JOIN users u ON u.id = s.user_id WHERE u.id IS NULL) +
  (SELECT COUNT(*) FROM settlements s LEFT JOIN expense_groups g ON g.id = s.group_id WHERE g.id IS NULL) +
  (SELECT COUNT(*) FROM settlements s LEFT JOIN users u ON u.id = s.from_user_id WHERE u.id IS NULL) +
  (SELECT COUNT(*) FROM settlements s LEFT JOIN users u ON u.id = s.to_user_id WHERE u.id IS NULL) +
  (SELECT COUNT(*) FROM settlements s LEFT JOIN users u ON u.id = s.recorded_by_user_id WHERE u.id IS NULL)
'@
	if ([int]$brokenReferences -ne 0) {
		throw 'The disposable database contains orphaned records.'
	}
	$unbalancedExpenses = Invoke-DbQuery @'
SELECT COUNT(*)
FROM expenses e
LEFT JOIN (
  SELECT expense_id, SUM(amount) AS split_total
  FROM expense_splits
  GROUP BY expense_id
) s ON s.expense_id = e.id
WHERE s.expense_id IS NULL OR e.amount <> s.split_total
'@
	if ([int]$unbalancedExpenses -ne 0) {
		throw 'Expense totals and persisted split totals do not reconcile.'
	}
}

function Get-TableCounts {
	return (Invoke-DbQuery @'
SELECT CONCAT(
  (SELECT COUNT(*) FROM users), ',',
  (SELECT COUNT(*) FROM expense_groups), ',',
  (SELECT COUNT(*) FROM group_members), ',',
  (SELECT COUNT(*) FROM expenses), ',',
  (SELECT COUNT(*) FROM expense_splits), ',',
  (SELECT COUNT(*) FROM settlements)
)
'@)
}

function Get-SchemathesisRequestCount {
	if (-not (Test-Path -LiteralPath $proxyCountPath)) {
		return 0
	}
	return [System.IO.File]::ReadAllLines($proxyCountPath).Length
}

function Invoke-Schemathesis {
	param(
		[string]$Name,
		[string]$Token,
		[string]$IncludePath,
		[string]$ExcludePath,
		[int]$Examples,
		[int]$TimeBudget,
		[string]$Phases
	)

	$requestsBefore = Get-SchemathesisRequestCount
	$env:PYTHONUTF8 = '1'
	$env:PYTHONIOENCODING = 'utf-8'
	$env:NO_COLOR = '1'
	$unexpectedMethods = '["GET", "PUT", "POST", "DELETE", "OPTIONS", "PATCH"]'
	if ($Name -eq 'unauthenticated') {
		$unexpectedMethods = '[]'
	}
	$arguments = @(
		'--no-color', 'run', $schemaPath,
		'--url', $schemathesisBaseUrl,
		'--workers', '1',
		'--max-examples', [string]$Examples,
		'--max-time', [string]$TimeBudget,
		'--max-failures', '25',
		'--rate-limit', '5/s',
		'--request-timeout', '5',
		'--max-response-time', '5',
		'--phases', $Phases,
		'--seed', '20261030',
		'--generation-deterministic',
		'--output-sanitize', 'true',
		'--output-truncate', 'true',
		'--report', 'junit',
		'--report-junit-path', (Join-Path $reportRoot "$reportPrefix-$Name.xml"),
		'--report-dir', (Join-Path $reportRoot "$reportPrefix-$Name")
	)
	if ($Phases -eq 'stateful') {
		$arguments += @('--exclude-operation-id-regex', '^createGroup$')
	}
	$previousAuthToken = [Environment]::GetEnvironmentVariable('SPLITSMART_SCHEMATHESIS_BEARER', 'Process')
	$authConfigPath = Join-Path $reportRoot "$reportPrefix-$Name-auth.toml"
	$authConfig = '[auth]' + "`r`n" +
			'auto-signup = false' + "`r`n" +
			"`r`n[phases.coverage]`r`n" +
			"unexpected-methods = $unexpectedMethods" + "`r`n"
	foreach ($operationName in @(
		'POST /api/auth/register',
		'POST /api/groups/{groupId}/expenses',
		'PUT /api/groups/{groupId}/expenses/{expenseId}',
		'POST /api/groups/{groupId}/settlements'
	)) {
		$authConfig += "`r`n[[operations]]`r`n" +
				"include-name = `"$operationName`"`r`n" +
				'checks.positive_data_acceptance.expected-statuses = ["2xx", "3xx", "400", "401", "403", "404", "409", "429", "5xx", "422"]' +
				"`r`n"
	}
	if (-not [string]::IsNullOrWhiteSpace($Token)) {
		$authConfig += "`r`n" + '[auth.openapi.bearerAuth]' + "`r`n" +
				'bearer = "${SPLITSMART_SCHEMATHESIS_BEARER}"' + "`r`n"
	}
	[System.IO.File]::WriteAllText($authConfigPath, $authConfig, (New-Object System.Text.UTF8Encoding($false)))
	$arguments = @('--config-file', $authConfigPath) + $arguments
	if (-not [string]::IsNullOrWhiteSpace($IncludePath)) {
		$arguments += @('--include-path-regex', $IncludePath)
	}
	if (-not [string]::IsNullOrWhiteSpace($ExcludePath)) {
		$arguments += @('--exclude-path-regex', $ExcludePath)
	}

	$logPath = Join-Path $reportRoot "$reportPrefix-$Name.log"
	try {
		[Environment]::SetEnvironmentVariable(
				'SPLITSMART_SCHEMATHESIS_BEARER',
				$(if ([string]::IsNullOrWhiteSpace($Token)) { $null } else { $Token }),
				'Process')
		$output = & $schemathesisPath @arguments 2>&1
		$exitCode = $LASTEXITCODE
	} finally {
		[Environment]::SetEnvironmentVariable('SPLITSMART_SCHEMATHESIS_BEARER', $previousAuthToken, 'Process')
		if ($null -ne $authConfigPath -and (Test-Path $authConfigPath)) {
			Remove-Item -LiteralPath $authConfigPath -Force
		}
	}
	$output | Set-Content -Path $logPath -Encoding UTF8
	$junitPath = Join-Path $reportRoot "$reportPrefix-$Name.xml"
	$caseCount = 0
	$failureCount = 0
	$statefulScenarioCount = 0
	$linkMetrics = $null
	$requestsAfter = Get-SchemathesisRequestCount
	$httpRequestCount = $requestsAfter - $requestsBefore
	if ($httpRequestCount -lt 0) {
		throw "HTTP request counter decreased during the $Name scan."
	}
	if ($httpRequestCount -eq 0) {
		throw "No HTTP requests were recorded for the $Name scan."
	}
	if (Test-Path $junitPath) {
		[xml]$junit = Get-Content -Path $junitPath -Raw
		$suites = $junit.SelectNodes('//testsuite')
		foreach ($suite in $suites) {
			$caseCount += [int]$suite.GetAttribute('tests')
		}
		$failureCount = @($junit.SelectNodes('//failure | //error')).Count
		if ($caseCount -eq 0 -and $junit.DocumentElement.Name -eq 'testsuites') {
			$caseCount = [int]$junit.DocumentElement.GetAttribute('tests')
		}
	}
	if ($exitCode -ge 2 -or $caseCount -le 0) {
		throw "Schemathesis did not execute test cases successfully; inspect sanitized log $logPath."
	}
	$outputText = $output | Out-String
	$generatedCaseCount = $null
	if ($outputText -match '(?m)^\s*Scenarios:\s*(\d+)\s*$') {
		$statefulScenarioCount = [int]$Matches[1]
	}
	if ($outputText -match '(?m)^\s*API Links:\s*(\d+) covered / (\d+) selected / (\d+) total(?:\s+\((\d+) inferred\))?\s*$') {
		$linkMetrics = [PSCustomObject]@{
			Covered = [int]$Matches[1]
			Selected = [int]$Matches[2]
			Total = [int]$Matches[3]
			Inferred = if ([string]::IsNullOrWhiteSpace($Matches[4])) { 0 } else { [int]$Matches[4] }
		}
	}
	if ($outputText -match '(?m)^\s*(\d+)\s+generated,\s+\d+\s+(?:found|passed|failed|skipped)\b') {
		$generatedCaseCount = [int]$Matches[1]
	}
	if ($null -eq $generatedCaseCount -and $Phases -eq 'stateful') {
		$generatedCaseCount = 0
	}
	if ($null -eq $generatedCaseCount) {
		throw "Could not read the generated-case count for the $Name scan; inspect sanitized log $logPath."
	}
	if ($Phases -eq 'stateful' -and ($null -eq $linkMetrics -or $linkMetrics.Selected -eq 0)) {
		throw "Schemathesis did not report selected API links for the $Name run; inspect sanitized log $logPath."
	}
	$script:cliResults += [PSCustomObject]@{
		Name = $Name
		ExitCode = $exitCode
		GeneratedCases = $generatedCaseCount
		StatefulScenarios = $statefulScenarioCount
		ApiLinkCoverage = $linkMetrics
		JUnitTestCases = $caseCount
		Failures = $failureCount
		HttpRequests = $httpRequestCount
		Log = $logPath
		JUnit = $junitPath
	}
	Write-Host "$Name`: $httpRequestCount HTTP requests; $generatedCaseCount generated cases; $failureCount failed checks (Schemathesis exit $exitCode)."
}

$initialDiskFree = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
$initialListeners = @{
	MySQL = @(Get-ListenerOwners -Port 3306)
	LiveApi = @(Get-ListenerOwners -Port 8081)
}
$initialContainers = @(& docker ps --quiet | Sort-Object)
$initialImageId = (& docker image inspect $image --format '{{.Id}}' 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($initialImageId)) {
	throw "Required cached image $image is not available; no image pull will be attempted."
}
if ($initialDiskFree -lt 5) {
	throw "Only $initialDiskFree GiB is available on C:; refusing to run the audit."
}
if ((Get-ListenerOwners -Port $ApiPort).Count -gt 0) {
	throw "Loopback API port $ApiPort is already in use; no listener will be replaced."
}
if (-not (Test-Path $jarPath) -or -not (Test-Path $schemathesisPath)) {
	throw 'Build the backend jar and install Schemathesis in backend/.venv-schemathesis before running this script.'
}
$javaHome = Join-Path $HOME '.jdks\temurin-21'
$javaPath = Join-Path $javaHome 'bin\java.exe'
$javaRelease = Join-Path $javaHome 'release'
if (-not (Test-Path $javaPath) -or -not (Test-Path $javaRelease) `
		-or (Get-Content $javaRelease | Select-String '^JAVA_VERSION="21\.') -eq $null) {
	throw 'Java 21 is required to launch the isolated backend.'
}

New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null
$schemaPath = Join-Path $reportRoot "$reportPrefix-openapi.json"
$databasePassword = New-RandomSecret
$databaseRootPassword = New-RandomSecret
$jwtSecret = New-RandomSecret
$accountPassword = "Aa9!$runId-Disposable"
$appStdoutPath = Join-Path $reportRoot "$reportPrefix-backend.stdout.log"
$appStderrPath = Join-Path $reportRoot "$reportPrefix-backend.stderr.log"
$schemathesisVersionOutput = (& $schemathesisPath --version 2>&1 | Out-String).Trim()
if ($schemathesisVersionOutput -notmatch '(\d+\.\d+\.\d+)') {
	throw 'Could not determine the installed Schemathesis version.'
}
$schemathesisVersion = $Matches[1]

try {
	$containerResult = Invoke-DockerCommand -Environment @{
		MYSQL_DATABASE = $databaseName
		MYSQL_USER = $databaseUser
		MYSQL_PASSWORD = $databasePassword
		MYSQL_ROOT_PASSWORD = $databaseRootPassword
	} -Arguments @(
		'run', '--detach',
		'--name', $containerName,
		'--label', 'com.splitsmart.audit.phase=4',
		'--label', "com.splitsmart.audit.run=$runId",
		'--publish', '127.0.0.1::3306',
		'--env', 'MYSQL_DATABASE',
		'--env', 'MYSQL_USER',
		'--env', 'MYSQL_PASSWORD',
		'--env', 'MYSQL_ROOT_PASSWORD',
		$image
	)
	if ($containerResult.ExitCode -ne 0) {
		$message = (($containerResult.Output | ForEach-Object { [string]$_ }) -join ' ').Trim()
		if ([string]::IsNullOrWhiteSpace($message)) {
			throw 'Could not create the disposable MySQL container.'
		}
		throw "Could not create the disposable MySQL container: $message"
	}
	$containerResult = $containerResult.Output
	$containerId = ([string]$containerResult).Trim()
	$inspectedId = (& docker inspect --format '{{.Id}}' $containerName).Trim()
	$containerLabels = (& docker inspect --format '{{json .Config.Labels}}' $containerName | ConvertFrom-Json)
	$phaseLabel = [string]$containerLabels.'com.splitsmart.audit.phase'
	$runLabel = [string]$containerLabels.'com.splitsmart.audit.run'
	if ($inspectedId -ne $containerId -or $phaseLabel -ne '4' -or $runLabel -ne $runId) {
		throw 'The MySQL container identity could not be verified.'
	}
	$portMapping = (& docker port $containerName 3306/tcp).Trim()
	if ($portMapping -notmatch '^127\.0\.0\.1:(\d+)$') {
		throw 'The disposable MySQL port is not bound exclusively to loopback.'
	}
	$databasePort = [int]$Matches[1]
	if ($databasePort -eq 3306) {
		throw 'The disposable MySQL container unexpectedly maps to the development MySQL port.'
	}

	$databaseReady = $false
	for ($attempt = 0; $attempt -lt 60; $attempt++) {
		$ping = Invoke-DockerCommand -Environment @{ MYSQL_PWD = $databaseRootPassword } -Arguments @(
			'exec', '--env', 'MYSQL_PWD', $containerName,
			'mysqladmin', 'ping', '--host=127.0.0.1', '--user=root', '--silent'
		)
		if ($ping.ExitCode -eq 0) {
			$databaseReady = $true
			break
		}
		Start-Sleep -Seconds 2
	}
	if (-not $databaseReady) {
		throw 'Disposable MySQL did not become ready within 120 seconds.'
	}

	$processInfo = New-Object System.Diagnostics.ProcessStartInfo
	$processInfo.FileName = $javaPath
	$processInfo.WorkingDirectory = $backendRoot
	$processInfo.Arguments = "-jar `"$jarPath`" --server.address=127.0.0.1 --server.port=$ApiPort --spring.profiles.active=schemathesis"
	$processInfo.UseShellExecute = $false
	$processInfo.CreateNoWindow = $true
	$processInfo.RedirectStandardOutput = $true
	$processInfo.RedirectStandardError = $true
	$processInfo.EnvironmentVariables['DB_URL'] = "jdbc:mysql://127.0.0.1:${databasePort}/${databaseName}?allowPublicKeyRetrieval=true"
	$processInfo.EnvironmentVariables['DB_USERNAME'] = $databaseUser
	$processInfo.EnvironmentVariables['DB_PASSWORD'] = $databasePassword
	$processInfo.EnvironmentVariables['JWT_SECRET'] = $jwtSecret
	$processInfo.EnvironmentVariables['APP_CORS_ALLOWED_ORIGINS'] = 'http://127.0.0.1:5173'
	$apiProcess = [System.Diagnostics.Process]::Start($processInfo)
	$stdoutTask = $apiProcess.StandardOutput.ReadToEndAsync()
	$stderrTask = $apiProcess.StandardError.ReadToEndAsync()

	$ready = $false
	$lastHealthError = 'No response was received.'
	for ($attempt = 0; $attempt -lt 60; $attempt++) {
		if ($apiProcess.HasExited) {
			throw "The isolated backend exited during startup (exit $($apiProcess.ExitCode))."
		}
		try {
			$health = Invoke-Api -Method 'GET' -Path '/api/health' -Token '' -Body $null
			if ($health.StatusCode -eq 200) {
				$ready = $true
				break
			}
			$lastHealthError = "HTTP $($health.StatusCode)"
		} catch {
			$lastHealthError = $_.Exception.Message
		}
		Start-Sleep -Seconds 2
	}
	if (-not $ready) {
		throw "The isolated API did not become ready within 120 seconds. Last health probe: $lastHealthError"
	}
	$apiListeners = @(Get-ListenerOwners -Port $ApiPort)
	if ($apiListeners.Count -ne 1 -or $apiListeners[0] -ne [string]$apiProcess.Id) {
		throw 'The isolated API port is not owned exclusively by the process started by this run.'
	}

	$proxyScriptPath = Join-Path $PSScriptRoot 'schemathesis-counting-proxy.py'
	$pythonPath = (Get-Command python -ErrorAction Stop).Source
	$proxyInfo = New-Object System.Diagnostics.ProcessStartInfo
	$proxyInfo.FileName = $pythonPath
	$proxyInfo.WorkingDirectory = $backendRoot
	$proxyInfo.Arguments = "-u `"$proxyScriptPath`" --target `"$baseUrl`" --counter-file `"$proxyCountPath`" --port 0"
	$proxyInfo.UseShellExecute = $false
	$proxyInfo.CreateNoWindow = $true
	$proxyInfo.RedirectStandardOutput = $true
	$proxyInfo.RedirectStandardError = $true
	$proxyProcess = [System.Diagnostics.Process]::Start($proxyInfo)
	$proxyStderrTask = $proxyProcess.StandardError.ReadToEndAsync()
	$proxyPortTask = $proxyProcess.StandardOutput.ReadLineAsync()
	if (-not $proxyPortTask.Wait(10000)) {
		throw 'The Schemathesis counting proxy did not report its loopback port within 10 seconds.'
	}
	$proxyReady = $proxyPortTask.GetAwaiter().GetResult()
	if ($proxyProcess.HasExited -or $proxyReady -notmatch '^READY (\d+)$') {
		throw 'The Schemathesis counting proxy failed to start.'
	}
	$proxyPort = [int]$Matches[1]
	$schemathesisBaseUrl = "http://127.0.0.1:$proxyPort"
	$proxyListeners = @(Get-ListenerOwners -Port $proxyPort)
	if ($proxyListeners.Count -ne 1 -or $proxyListeners[0] -ne [string]$proxyProcess.Id) {
		throw 'The Schemathesis counting proxy is not owned exclusively by the process started by this run.'
	}

	$migrationResult = Invoke-DbQuery "SELECT CONCAT(COUNT(*), ',', SUM(success = 1)) FROM flyway_schema_history"
	if ($migrationResult -ne '3,3') {
		throw "Flyway migration state is not the expected three successful migrations: $migrationResult."
	}
	$initialCounts = Get-TableCounts
	if ($initialCounts -ne '0,0,0,0,0,0') {
		throw 'Disposable database was not empty before audit setup.'
	}
	$actualDatabase = Invoke-DbQuery 'SELECT DATABASE()'
	if ($actualDatabase -ne $databaseName) {
		throw 'The container database identity does not match the generated database name.'
	}

	$emails = @(
		"st-a-$runId@example.invalid",
		"stateful-member@example.invalid",
		"st-c-$runId@example.invalid",
		"st-d-$runId@example.invalid"
	)
	$blankRegistration = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Token '' -Body @{
		name = ''
		email = "st-blank-$runId@example.invalid"
		password = $accountPassword
	}
	Assert-Status $blankRegistration 400 'Blank registration name'
	if ((Get-TableCounts) -ne $initialCounts) {
		throw 'Rejected blank-name registration changed disposable database row counts.'
	}
	$users = @()
	$tokens = @()
	$names = @('Creator A', 'Member B', 'Member C', 'Outsider D')
	for ($index = 0; $index -lt 4; $index++) {
		$registered = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Token '' -Body @{
			name = "$($names[$index]) $runId"
			email = $emails[$index]
			password = $accountPassword
		}
		Assert-Status $registered 201 'Disposable account registration'
		$users += $registered.Body
		$login = Invoke-Api -Method 'POST' -Path '/api/auth/login' -Token '' -Body @{
			email = $emails[$index]
			password = $accountPassword
		}
		Assert-Status $login 200 'Disposable account login'
		if ([string]::IsNullOrWhiteSpace($login.Body.accessToken)) {
			throw 'Disposable account login did not return a bearer token.'
		}
		$tokens += [string]$login.Body.accessToken
	}
	if ((Invoke-DbQuery "SELECT COUNT(*) FROM users WHERE email = '$($emails[0])'") -ne '1') {
		throw 'API registration was not persisted in the isolated container database.'
	}

	$unauthorized = Invoke-Api -Method 'GET' -Path '/api/groups' -Token '' -Body $null
	Assert-Status $unauthorized 401 'Missing bearer token'
	$invalidToken = Invoke-Api -Method 'GET' -Path '/api/groups' -Token 'not-a-valid-jwt' -Body $null
	Assert-Status $invalidToken 401 'Invalid bearer token'
	$duplicate = Invoke-Api -Method 'POST' -Path '/api/auth/register' -Token '' -Body @{
		name = 'Duplicate account'
		email = $emails[0]
		password = $accountPassword
	}
	Assert-Status $duplicate 409 'Duplicate registration'
	$me = Invoke-Api -Method 'GET' -Path '/api/auth/me' -Token $tokens[0] -Body $null
	Assert-Status $me 200 'Authenticated account'
	$traceProbes = @(
		(Invoke-TraceProbe -Path '/api/health' -Token '' -ProbeValue "trace-probe-$runId-unauthenticated"),
		(Invoke-TraceProbe -Path '/api/auth/me' -Token $tokens[0] -ProbeValue "trace-probe-$runId-authenticated")
	)

	$group = Invoke-Api -Method 'POST' -Path '/api/groups' -Token $tokens[0] -Body @{
		name = "Phase 4 $runId"
	}
	Assert-Status $group 201 'Create test group'
	$groupId = [long]$group.Body.id
	$groupList = Invoke-Api -Method 'GET' -Path '/api/groups' -Token $tokens[0] -Body $null
	Assert-Status $groupList 200 'List creator groups'
	foreach ($index in @(1, 2)) {
		$member = Invoke-Api -Method 'POST' -Path "/api/groups/$groupId/members" -Token $tokens[0] -Body @{
			email = $emails[$index]
		}
		Assert-Status $member 201 'Creator adds group member'
	}
	$members = Invoke-Api -Method 'GET' -Path "/api/groups/$groupId/members" -Token $tokens[1] -Body $null
	Assert-Status $members 200 'Member lists group members'
	$unauthorizedAdd = Invoke-Api -Method 'POST' -Path "/api/groups/$groupId/members" -Token $tokens[1] -Body @{
		email = $emails[3]
	}
	Assert-Status $unauthorizedAdd 403 'Noncreator member addition'

	$expenseBody = @{
		description = "Fuzz fixture $runId"
		amount = [decimal]'100.00'
		paidByUserId = [long]$users[0].id
		currencyCode = 'INR'
		splitType = 'EXACT'
		splits = @(
			@{ userId = [long]$users[0].id; amount = [decimal]'50.00' },
			@{ userId = [long]$users[1].id; amount = [decimal]'30.00' },
			@{ userId = [long]$users[2].id; amount = [decimal]'20.00' }
		)
	}
	$expense = Invoke-Api -Method 'POST' -Path "/api/groups/$groupId/expenses" -Token $tokens[0] -Body $expenseBody
	Assert-Status $expense 201 'Create test expense'
	$expenseId = [long]$expense.Body.id
	$listExpenses = Invoke-Api -Method 'GET' -Path "/api/groups/$groupId/expenses" -Token $tokens[1] -Body $null
	Assert-Status $listExpenses 200 'Member lists expenses'
	$getExpense = Invoke-Api -Method 'GET' -Path "/api/groups/$groupId/expenses/$expenseId" -Token $tokens[1] -Body $null
	Assert-Status $getExpense 200 'Member reads expense'
	$balances = Invoke-Api -Method 'GET' -Path "/api/groups/$groupId/balances" -Token $tokens[0] -Body $null
	Assert-Status $balances 200 'Read group balances'

	$updatedExpenseBody = @{
		description = "Updated fixture $runId"
		amount = [decimal]'100.00'
		paidByUserId = [long]$users[0].id
		currencyCode = 'INR'
		splitType = 'EXACT'
		splits = @(
			@{ userId = [long]$users[0].id; amount = [decimal]'50.00' },
			@{ userId = [long]$users[1].id; amount = [decimal]'30.00' },
			@{ userId = [long]$users[2].id; amount = [decimal]'20.00' }
		)
	}
	$noncreatorUpdate = Invoke-Api -Method 'PUT' -Path "/api/groups/$groupId/expenses/$expenseId" -Token $tokens[1] -Body $updatedExpenseBody
	Assert-Status $noncreatorUpdate 403 'Noncreator expense update'
	$noncreatorDelete = Invoke-Api -Method 'DELETE' -Path "/api/groups/$groupId/expenses/$expenseId" -Token $tokens[1] -Body $null
	Assert-Status $noncreatorDelete 403 'Noncreator expense deletion'
	$validUpdate = Invoke-Api -Method 'PUT' -Path "/api/groups/$groupId/expenses/$expenseId" -Token $tokens[0] -Body $updatedExpenseBody
	Assert-Status $validUpdate 200 'Creator updates expense'
	$beforeInvalidCreate = Get-TableCounts
	$invalidCreateBody = $updatedExpenseBody.Clone()
	$invalidCreateBody.description = "Invalid fixture $runId"
	$invalidCreateBody.splits = @(
		@{ userId = [long]$users[0].id; amount = [decimal]'50.00' },
		@{ userId = [long]$users[1].id; amount = [decimal]'30.00' },
		@{ userId = [long]$users[2].id; amount = [decimal]'19.99' }
	)
	$invalidCreate = Invoke-Api -Method 'POST' -Path "/api/groups/$groupId/expenses" -Token $tokens[0] -Body $invalidCreateBody
	Assert-Status $invalidCreate 400 'Invalid exact split creation'
	if ((Get-TableCounts) -ne $beforeInvalidCreate) {
		throw 'Invalid expense creation changed disposable database row counts.'
	}
	$beforeInvalidUpdate = Invoke-Api -Method 'GET' -Path "/api/groups/$groupId/expenses/$expenseId" -Token $tokens[0] -Body $null
	$invalidUpdateBody = $updatedExpenseBody.Clone()
	$invalidUpdateBody.splits = $invalidCreateBody.splits
	$invalidUpdate = Invoke-Api -Method 'PUT' -Path "/api/groups/$groupId/expenses/$expenseId" -Token $tokens[0] -Body $invalidUpdateBody
	Assert-Status $invalidUpdate 400 'Invalid expense update'
	$afterInvalidUpdate = Invoke-Api -Method 'GET' -Path "/api/groups/$groupId/expenses/$expenseId" -Token $tokens[0] -Body $null
	Assert-Status $afterInvalidUpdate 200 'Read expense after rejected update'
	if ($afterInvalidUpdate.Body.description -ne $beforeInvalidUpdate.Body.description `
			-or $afterInvalidUpdate.Body.amount -ne $beforeInvalidUpdate.Body.amount `
			-or $afterInvalidUpdate.Body.splits.Count -ne $beforeInvalidUpdate.Body.splits.Count) {
		throw 'Rejected invalid expense update altered the persisted expense.'
	}

	$deleteBody = @{
		description = "Delete fixture $runId"
		amount = [decimal]'25.00'
		paidByUserId = [long]$users[0].id
		currencyCode = 'INR'
		splitType = 'EXACT'
		splits = @(@{ userId = [long]$users[2].id; amount = [decimal]'25.00' })
	}
	$deleteExpense = Invoke-Api -Method 'POST' -Path "/api/groups/$groupId/expenses" -Token $tokens[0] -Body $deleteBody
	Assert-Status $deleteExpense 201 'Create deletable expense'
	$deleteResponse = Invoke-Api -Method 'DELETE' -Path "/api/groups/$groupId/expenses/$($deleteExpense.Body.id)" -Token $tokens[0] -Body $null
	Assert-Status $deleteResponse 204 'Creator deletes expense'

	$outsiderGroup = Invoke-Api -Method 'POST' -Path '/api/groups' -Token $tokens[3] -Body @{
		name = "Outsider group $runId"
	}
	Assert-Status $outsiderGroup 201 'Create separate outsider group'
	$outsiderGroupId = [long]$outsiderGroup.Body.id
	$privacyPaths = @(
		"/api/groups/$groupId/members",
		"/api/groups/$groupId/expenses",
		"/api/groups/$groupId/balances",
		"/api/groups/$groupId/settlements",
		"/api/groups/$groupId/settlement-suggestions",
		"/api/groups/$groupId/expenses/$expenseId",
		"/api/groups/$outsiderGroupId/expenses/$expenseId"
	)
	foreach ($path in $privacyPaths) {
		$privateResponse = Invoke-Api -Method 'GET' -Path $path -Token $tokens[3] -Body $null
		Assert-Status $privateResponse 404 'Outsider access to private group/resource'
	}

	$validRequestId = [Guid]::NewGuid().ToString()
	$settlementBody = @{
		fromUserId = [long]$users[1].id
		toUserId = [long]$users[0].id
		amount = [decimal]'10.00'
		requestId = $validRequestId
		note = 'Disposable audit settlement'
	}
	$beforeInvalidSettlement = Get-TableCounts
	$negativeSettlement = $settlementBody.Clone()
	$negativeSettlement.amount = [decimal]'-1.00'
	$negativeResponse = Invoke-Api -Method 'POST' -Path "/api/groups/$groupId/settlements" -Token $tokens[1] -Body $negativeSettlement
	Assert-Status $negativeResponse 400 'Negative settlement amount'
	$spoofedSettlement = $settlementBody.Clone()
	$spoofedResponse = Invoke-Api -Method 'POST' -Path "/api/groups/$groupId/settlements" -Token $tokens[0] -Body $spoofedSettlement
	Assert-Status $spoofedResponse 403 'Settlement sender spoofing'
	$outsiderRecipient = $settlementBody.Clone()
	$outsiderRecipient.toUserId = [long]$users[3].id
	$outsiderRecipient.requestId = [Guid]::NewGuid().ToString()
	$outsiderRecipientResponse = Invoke-Api -Method 'POST' -Path "/api/groups/$groupId/settlements" -Token $tokens[1] -Body $outsiderRecipient
	Assert-Status $outsiderRecipientResponse 400 'Out-of-group settlement recipient'
	if ((Get-TableCounts) -ne $beforeInvalidSettlement) {
		throw 'Rejected settlement requests changed disposable database row counts.'
	}
	$settlement = Invoke-Api -Method 'POST' -Path "/api/groups/$groupId/settlements" -Token $tokens[1] -Body $settlementBody
	Assert-Status $settlement 201 'Record settlement as the debtor'
	$duplicateSettlement = Invoke-Api -Method 'POST' -Path "/api/groups/$groupId/settlements" -Token $tokens[1] -Body $settlementBody
	Assert-Status $duplicateSettlement 409 'Duplicate settlement request ID'
	if ((Invoke-DbQuery "SELECT COUNT(*) FROM settlements WHERE group_id = $groupId AND request_id = '$validRequestId'") -ne '1') {
		throw 'Duplicate settlement request ID created more than one database row.'
	}
	$settlements = Invoke-Api -Method 'GET' -Path "/api/groups/$groupId/settlements" -Token $tokens[1] -Body $null
	Assert-Status $settlements 200 'Member reads settlements'
	$suggestions = Invoke-Api -Method 'GET' -Path "/api/groups/$groupId/settlement-suggestions" -Token $tokens[1] -Body $null
	Assert-Status $suggestions 200 'Member reads settlement suggestions'

	Assert-SafeDatabase
	$countsBeforeFuzz = Get-TableCounts

	$schemaResponse = Invoke-Api -Method 'GET' -Path '/api/openapi' -Token $tokens[0] -Body $null
	Assert-Status $schemaResponse 200 'Authenticated OpenAPI schema'
	[System.IO.File]::WriteAllText($schemaPath, $schemaResponse.Raw, (New-Object System.Text.UTF8Encoding($false)))
	$schema = ConvertFrom-Json -InputObject $schemaResponse.Raw
	$expectedPaths = @(
		'/api/health',
		'/api/auth/register',
		'/api/auth/login',
		'/api/auth/me',
		'/api/groups',
		'/api/groups/{groupId}/members',
		'/api/groups/{groupId}/expenses',
		'/api/groups/{groupId}/expenses/{expenseId}',
		'/api/groups/{groupId}/balances',
		'/api/groups/{groupId}/settlements',
		'/api/groups/{groupId}/settlement-suggestions'
	)
	foreach ($path in $expectedPaths) {
		if ($null -eq $schema.paths.PSObject.Properties[$path]) {
			throw "Expected actual controller route $path is missing from OpenAPI."
		}
	}
	$httpMethods = @('get', 'post', 'put', 'delete', 'patch', 'options', 'head', 'trace')
	foreach ($pathProperty in $schema.paths.PSObject.Properties) {
		foreach ($methodProperty in $pathProperty.Value.PSObject.Properties) {
			if ($httpMethods -contains $methodProperty.Name.ToLowerInvariant()) {
				$schemaOperationCount++
			}
		}
	}
	if ($schemaOperationCount -ne 17) {
		throw "Expected 17 controller operations in OpenAPI but found $schemaOperationCount."
	}
	$linkCount = 0
	foreach ($pathProperty in $schema.paths.PSObject.Properties) {
		foreach ($methodProperty in $pathProperty.Value.PSObject.Properties) {
			if ($httpMethods -contains $methodProperty.Name.ToLowerInvariant()) {
				foreach ($responseProperty in $methodProperty.Value.responses.PSObject.Properties) {
					if ($null -ne $responseProperty.Value.links) {
						$responseLinkCount = @($responseProperty.Value.links.PSObject.Properties).Count
						$linkCount = [int]$linkCount + [int]$responseLinkCount
					}
				}
			}
		}
	}
	if ($linkCount -ne 12) {
		throw "Expected 12 explicit API links for stateful coverage but found $linkCount."
	}
	$scheme = $schema.components.securitySchemes.bearerAuth
	if ($scheme.type -ne 'http' -or $scheme.scheme -ne 'bearer' -or $scheme.bearerFormat -ne 'JWT') {
		throw 'OpenAPI does not declare the expected JWT bearer security scheme.'
	}
	$expectedOperations = [ordered]@{
		'get /api/health' = @('200', $false)
		'post /api/auth/register' = @('201', $false)
		'post /api/auth/login' = @('200', $false)
		'get /api/auth/me' = @('200', $true)
		'post /api/groups' = @('201', $true)
		'get /api/groups' = @('200', $true)
		'post /api/groups/{groupId}/members' = @('201', $true)
		'get /api/groups/{groupId}/members' = @('200', $true)
		'post /api/groups/{groupId}/expenses' = @('201', $true)
		'get /api/groups/{groupId}/expenses' = @('200', $true)
		'get /api/groups/{groupId}/expenses/{expenseId}' = @('200', $true)
		'put /api/groups/{groupId}/expenses/{expenseId}' = @('200', $true)
		'delete /api/groups/{groupId}/expenses/{expenseId}' = @('204', $true)
		'get /api/groups/{groupId}/balances' = @('200', $true)
		'post /api/groups/{groupId}/settlements' = @('201', $true)
		'get /api/groups/{groupId}/settlements' = @('200', $true)
		'get /api/groups/{groupId}/settlement-suggestions' = @('200', $true)
	}
	foreach ($entry in $expectedOperations.GetEnumerator()) {
		$separator = $entry.Key.IndexOf(' ')
		$method = $entry.Key.Substring(0, $separator)
		$path = $entry.Key.Substring($separator + 1)
		$pathItem = $schema.paths.PSObject.Properties[$path]
		$operationProperty = $null
		if ($null -ne $pathItem) {
			$operationProperty = $pathItem.Value.PSObject.Properties[$method]
		}
		if ($null -eq $operationProperty) {
			throw "OpenAPI is missing controller operation $($entry.Key)."
		}
		$expectedStatus = [string]$entry.Value[0]
		if ($null -eq $operationProperty.Value.responses.PSObject.Properties[$expectedStatus]) {
			throw "OpenAPI is missing actual success response $expectedStatus for $($entry.Key)."
		}
		$isProtected = [bool]$entry.Value[1]
		if ($isProtected) {
			$hasBearerSecurity = @($operationProperty.Value.security | ForEach-Object {
				$null -ne $_.PSObject.Properties['bearerAuth']
			} | Where-Object { $_ }).Count -gt 0
			if (-not $hasBearerSecurity) {
				throw "OpenAPI does not require JWT bearer auth for $($entry.Key)."
			}
		} elseif ($null -ne $operationProperty.Value.security -and $operationProperty.Value.security.Count -gt 0) {
			throw "OpenAPI incorrectly requires authentication for $($entry.Key)."
		}
	}
	foreach ($operation in @(
		@('/api/groups/{groupId}/members', 'post'),
		@('/api/groups/{groupId}/members', 'get'),
		@('/api/groups/{groupId}/expenses', 'post'),
		@('/api/groups/{groupId}/expenses', 'get'),
		@('/api/groups/{groupId}/expenses/{expenseId}', 'get'),
		@('/api/groups/{groupId}/expenses/{expenseId}', 'put'),
		@('/api/groups/{groupId}/expenses/{expenseId}', 'delete'),
		@('/api/groups/{groupId}/balances', 'get'),
		@('/api/groups/{groupId}/settlements', 'post'),
		@('/api/groups/{groupId}/settlements', 'get'),
		@('/api/groups/{groupId}/settlement-suggestions', 'get')
	)) {
		$operationProperty = $schema.paths.PSObject.Properties[$operation[0]].Value.PSObject.Properties[$operation[1]]
		if ($null -eq $operationProperty.Value.responses.PSObject.Properties['400']) {
			throw "OpenAPI does not document invalid path IDs for $($operation[1]) $($operation[0])."
		}
	}
	$registrationNameSchema = $schema.components.schemas.RegisterRequest.properties.name
	if ($registrationNameSchema.minLength -ne 1 -or $registrationNameSchema.pattern -ne '\S') {
		throw 'OpenAPI does not prohibit blank registration names.'
	}
	foreach ($schemaName in @(
		'CreateExpenseRequest', 'CreateExpenseSplitRequest', 'CreateSettlementRequest'
	)) {
		$amountSchema = $schema.components.schemas.PSObject.Properties[$schemaName].Value.properties.amount
		if ([string]$amountSchema.exclusiveMaximum -ne '100000000000000000' -or
				$null -ne $amountSchema.maximum) {
			throw "OpenAPI does not represent the exclusive DECIMAL(19,2) amount limit for $schemaName."
		}
	}
	foreach ($field in @('splits', 'participantUserIds')) {
		$fieldTypes = @($schema.components.schemas.CreateExpenseRequest.properties.$field.type)
		if ($fieldTypes -notcontains 'null') {
			throw "OpenAPI does not allow null for optional expense field $field."
		}
	}
	foreach ($operation in @(
		@('/api/groups/{groupId}/expenses', 'post'),
		@('/api/groups/{groupId}/expenses/{expenseId}', 'put')
	)) {
		$pathValue = $schema.paths.PSObject.Properties[$operation[0]].Value
		$requestOperation = $pathValue.PSObject.Properties[$operation[1]].Value
		$requestSchema = $requestOperation.requestBody.content.'application/json'.schema
		if (@($requestSchema.oneOf).Count -ne 2 `
				-or $requestSchema.oneOf[0].'$ref' -notmatch 'CreateExpenseRequestExact$' `
				-or $requestSchema.oneOf[1].'$ref' -notmatch 'CreateExpenseRequestEqual$') {
			throw "OpenAPI does not constrain split modes for $($operation[1]) $($operation[0])."
		}
	}
	foreach ($schemaName in @(
		'RegisterRequest', 'LoginRequest', 'CreateGroupRequest', 'AddGroupMemberRequest',
		'CreateExpenseRequest', 'CreateExpenseSplitRequest', 'CreateSettlementRequest',
		'RegisterResponse', 'LoginResponse', 'ApiErrorResponse', 'GroupResponse',
		'GroupMemberResponse', 'ExpenseResponse', 'ExpenseSplitResponse',
		'GroupBalanceResponse', 'MemberBalanceResponse', 'SettlementResponse',
		'SettlementSuggestionResponse', 'SuggestedTransferResponse'
	)) {
		if ($null -eq $schema.components.schemas.PSObject.Properties[$schemaName]) {
			throw "OpenAPI is missing DTO schema $schemaName."
		}
	}

	$schemathesisExeVersionOutput = (& $schemathesisPath --version 2>&1 | Out-String).Trim()
	$schemathesisExeVersion = ''
	if ($schemathesisExeVersionOutput -match '(\d+\.\d+\.\d+)') {
		$schemathesisExeVersion = $Matches[1]
	}
	if ($schemathesisExeVersion -ne $schemathesisVersion) {
		throw 'Schemathesis executable version changed during the audit.'
	}
	if ($Stateful) {
		Invoke-Schemathesis -Name 'creator-stateful' -Token $tokens[0] `
			-IncludePath '^/api/groups(?:/.*)?$' -ExcludePath '' -Examples 5 -TimeBudget 45 `
			-Phases 'stateful'
	} elseif ($Extended) {
		Invoke-Schemathesis -Name 'member-authenticated' -Token $tokens[1] `
			-IncludePath '^/api/groups/.*' -ExcludePath '' -Examples 8 -TimeBudget 30 `
			-Phases 'examples,coverage,fuzzing'
	} else {
		Invoke-Schemathesis -Name 'unauthenticated' -Token '' `
			-IncludePath '^/api/(health|auth/.*)$' -ExcludePath '' -Examples 5 -TimeBudget 10 `
			-Phases 'examples,coverage,fuzzing'
		Invoke-Schemathesis -Name 'creator-authenticated' -Token $tokens[0] `
			-IncludePath '' -ExcludePath '^/api/(health|auth/(register|login))$' -Examples 5 -TimeBudget 30 `
			-Phases 'examples,coverage,fuzzing'
	}
	$countsAfterFuzz = Get-TableCounts
	Assert-SafeDatabase

	$schemathesisHttpRequests = [int](@($cliResults | ForEach-Object { $_.HttpRequests } |
			Measure-Object -Sum).Sum)
	$otherApiRequests = $apiRequestCount
	$totalHttpRequests = $schemathesisHttpRequests + $otherApiRequests
	$metadata = [ordered]@{
		schemathesisVersion = $schemathesisVersion
		pythonVersion = (& python --version 2>&1 | Out-String).Trim()
		openApiVersion = $schema.openapi
		openApiOperations = $schemaOperationCount
		testedOperations = if ($Stateful) {
			'stateful group-resource links, authenticated as creator'
		} elseif ($Extended) {
			'group operations, authenticated as member B'
		} else {
			'17 initial public/creator API operations'
		}
		generatedCases = @($cliResults | ForEach-Object { $_.GeneratedCases } | Measure-Object -Sum).Sum
		junitTestCases = @($cliResults | ForEach-Object { $_.JUnitTestCases } | Measure-Object -Sum).Sum
		failures = @($cliResults | ForEach-Object { $_.Failures } | Measure-Object -Sum).Sum
		schemathesisHttpRequests = $schemathesisHttpRequests
		otherApiRequests = $otherApiRequests
		totalHttpRequests = $totalHttpRequests
		initialDatabaseCounts = $initialCounts
		databaseCountsBeforeFuzz = $countsBeforeFuzz
		databaseCountsAfterFuzz = $countsAfterFuzz
		explicitApiLinks = $linkCount
		traceProbes = @($traceProbes)
		statefulScenarios = @($cliResults | ForEach-Object { $_.StatefulScenarios } |
				Measure-Object -Sum).Sum
		apiLinkCoverage = @($cliResults | Where-Object { $null -ne $_.ApiLinkCoverage } |
				ForEach-Object {
					[PSCustomObject]@{
						name = $_.Name
						covered = $_.ApiLinkCoverage.Covered
						selected = $_.ApiLinkCoverage.Selected
						total = $_.ApiLinkCoverage.Total
						inferred = $_.ApiLinkCoverage.Inferred
					}
				})
		flywaySuccessfulMigrations = 3
		apiPort = $ApiPort
		mysqlHostPort = $databasePort
		database = $databaseName
		dockerImage = $image
		cliRuns = @($cliResults)
	}
	$metadataPath = Join-Path $reportRoot "$reportPrefix-summary.json"
	$metadata | ConvertTo-Json -Depth 10 | Set-Content -Path $metadataPath -Encoding UTF8
	Write-Host "OpenAPI: $schemaOperationCount operations; Schemathesis $schemathesisVersion."
	Write-Host "Disposable DB: $databaseName on loopback port $databasePort; Flyway 3/3 successful."
	Write-Host "Database rows before/after fuzzing: $countsBeforeFuzz / $countsAfterFuzz."
	Write-Host "HTTP requests: $schemathesisHttpRequests by Schemathesis, $otherApiRequests other audit calls, $totalHttpRequests total."
	Write-Host "Sanitized reports: $reportRoot."
} finally {
	if ($null -ne $proxyProcess) {
		if (-not $proxyProcess.HasExited) {
			$proxyOwners = @(Get-ListenerOwners -Port $proxyPort)
			if ($proxyOwners.Count -eq 1 -and $proxyOwners[0] -eq [string]$proxyProcess.Id) {
				Stop-Process -Id $proxyProcess.Id -Force
				$proxyProcess.WaitForExit(15000) | Out-Null
			} elseif ($proxyOwners.Count -gt 0) {
				$cleanupFailure = "Refused to stop a counting proxy listener not owned by PID $($proxyProcess.Id)."
			} else {
				Stop-Process -Id $proxyProcess.Id -Force -ErrorAction SilentlyContinue
			}
		}
		if ($null -ne $proxyStderrTask) {
			$proxyStderr = $proxyStderrTask.GetAwaiter().GetResult()
			if (-not [string]::IsNullOrWhiteSpace($proxyStderr)) {
				$proxyStderrPath = Join-Path $reportRoot "$reportPrefix-proxy.stderr.log"
				[System.IO.File]::WriteAllText($proxyStderrPath, $proxyStderr)
			}
		}
		$proxyProcess.Dispose()
	}
	if ($null -ne $apiProcess) {
		if (-not $apiProcess.HasExited) {
			$apiOwners = @(Get-ListenerOwners -Port $ApiPort)
			if ($apiOwners.Count -eq 1 -and $apiOwners[0] -eq [string]$apiProcess.Id) {
				Stop-Process -Id $apiProcess.Id -Force
				$apiProcess.WaitForExit(15000) | Out-Null
			} elseif ($apiOwners.Count -gt 0) {
				$cleanupFailure = "Refused to stop an API listener not owned by PID $($apiProcess.Id)."
			} else {
				Stop-Process -Id $apiProcess.Id -Force -ErrorAction SilentlyContinue
			}
		}
		if ($null -ne $stdoutTask -and $null -ne $stderrTask) {
			[System.IO.File]::WriteAllText($appStdoutPath, $stdoutTask.GetAwaiter().GetResult())
			[System.IO.File]::WriteAllText($appStderrPath, $stderrTask.GetAwaiter().GetResult())
		}
		$apiProcess.Dispose()
	}
	if (Test-Path -LiteralPath $proxyCountPath) {
		Remove-Item -LiteralPath $proxyCountPath -Force
	}

	if (-not [string]::IsNullOrWhiteSpace($containerId)) {
		$currentId = & docker inspect --format '{{.Id}}' $containerName 2>$null
		if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace([string]$currentId) `
				-and ([string]$currentId).Trim() -eq $containerId) {
			$currentLabels = (& docker inspect --format '{{json .Config.Labels}}' $containerName | ConvertFrom-Json)
			$currentPhase = [string]$currentLabels.'com.splitsmart.audit.phase'
			$currentRun = [string]$currentLabels.'com.splitsmart.audit.run'
			if ($currentPhase -eq '4' -and $currentRun -eq $runId) {
				& docker stop $containerId 2>$null | Out-Null
				& docker rm $containerId 2>$null | Out-Null
			} else {
				$cleanupFailure = 'Refused to remove a container whose audit ownership labels changed.'
			}
		}
	}

	$finalListeners = @{
		MySQL = @(Get-ListenerOwners -Port 3306)
		LiveApi = @(Get-ListenerOwners -Port 8081)
		AuditApi = @(Get-ListenerOwners -Port $ApiPort)
	}
	if ($null -ne $proxyPort) {
		$finalListeners.Proxy = @(Get-ListenerOwners -Port $proxyPort)
	}
	if (($initialListeners.MySQL -join ',') -ne ($finalListeners.MySQL -join ',') `
			-or ($initialListeners.LiveApi -join ',') -ne ($finalListeners.LiveApi -join ',')) {
		$cleanupFailure = 'A protected MySQL or live API listener changed during this run.'
	}
	if ($finalListeners.AuditApi.Count -gt 0) {
		$cleanupFailure = "Audit API port $ApiPort remains occupied after cleanup."
	}
	if ($finalListeners.ContainsKey('Proxy') -and $finalListeners.Proxy.Count -gt 0) {
		$cleanupFailure = "Counting proxy port $proxyPort remains occupied after cleanup."
	}
	$finalContainers = @(& docker ps --quiet | Sort-Object)
	if (($initialContainers -join ',') -ne ($finalContainers -join ',')) {
		$cleanupFailure = 'Running Docker container identities differ from the preflight snapshot.'
	}
	if ((& docker image inspect $image --format '{{.Id}}' 2>$null) -ne $initialImageId.Trim()) {
		$cleanupFailure = 'The cached MySQL image changed unexpectedly.'
	}
	$finalDiskFree = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
	$diskResult = [ordered]@{ freeGiBBefore = $initialDiskFree; freeGiBAfter = $finalDiskFree }
	$diskResult | ConvertTo-Json | Set-Content -Path (Join-Path $reportRoot "$reportPrefix-disk.json") -Encoding UTF8
	Write-Host "C: free space: $initialDiskFree GiB before, $finalDiskFree GiB after."
	if ($null -ne $cleanupFailure) {
		throw $cleanupFailure
	}
}
