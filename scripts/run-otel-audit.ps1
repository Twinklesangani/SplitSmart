[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$jarPath = Join-Path $backendRoot 'target\splitsmart-backend-0.0.1-SNAPSHOT.jar'
$javaPath = Join-Path $HOME '.jdks\temurin-21\bin\java.exe'
$agentPath = Join-Path $env:LOCALAPPDATA 'SplitSmartAudit\otel\2.33.1\opentelemetry-javaagent.jar'
$collectorPath = Join-Path $env:LOCALAPPDATA 'SplitSmartAudit\otel\collector\0.164.0\otelcol.exe'
$reportRoot = Join-Path $backendRoot 'target\reports\opentelemetry'
$image = 'mysql:8.4.13'
$apiPort = 18081
$apiUrl = "http://127.0.0.1:$apiPort"
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$containerName = "splitsmart-otel-$runId"
$volumeName = "splitsmart-otel-data-$runId"
$databaseName = "splitsmart_otel_$runId"
$databaseUser = "otel_$runId"
$summaryPath = Join-Path $reportRoot "phase6-$runId-summary.json"
$collectorConfigPath = Join-Path $reportRoot "phase6-$runId-collector.yaml"
$databasePort = $null
$collectorPort = $null
$collectorConfigRemoved = $true
$containerId = $null
$volumeCreated = $false
$apiProcess = $null
$apiStdoutTask = $null
$apiStderrTask = $null
$collectorProcess = $null
$collectorStdoutTask = $null
$collectorStderrTask = $null
$lockProcess = $null
$requestRecords = [System.Collections.Generic.List[object]]::new()
$captureMode = 'none'
$apiMode = 'none'
$mysqlPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$mysqlRootPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$jwtSecretBytes = New-Object byte[] 48
$jwtSecretGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
	$jwtSecretGenerator.GetBytes($jwtSecretBytes)
} finally {
	$jwtSecretGenerator.Dispose()
}
$jwtSecret = [Convert]::ToBase64String($jwtSecretBytes)
[Array]::Clear($jwtSecretBytes, 0, $jwtSecretBytes.Length)
$jwtSecretBytes = $null
$testPassword = 'Phase6-Audit-Only-29!'
$cleanupFailure = $null
$protectedListenersPreserved = $true
$runFailure = $null
$safeFailureMessage = $null
$failureLine = $null
$summary = $null
$initialFreeGiB = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
$initialListeners = @{}
$initialContainers = @()
$initialVolumes = @()
$initialImageId = $null
$initialMigrationHashes = @{}
$protectedListenerPorts = @(3306, 8081)
$dockerPath = (Get-Command docker.exe -ErrorAction Stop).Source
$collectorVersion = '0.164.0'
$agentVersion = '2.33.1'

function Get-ListenerDetails {
	param([int]$Port)

	return @(
		Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
			Select-Object @{
				Name = 'LocalAddress'
				Expression = { [string]$_.LocalAddress }
			}, @{
				Name = 'OwnerId'
				Expression = { [string]$_.OwningProcess }
			} |
			Sort-Object LocalAddress, OwnerId -Unique
	)
}

function Test-LoopbackListener {
	param(
		[int]$Port,
		[string]$ExpectedProcessId
	)

	$listeners = @(Get-ListenerDetails -Port $Port)
	return ($listeners.Count -eq 1 `
		-and $listeners[0].OwnerId -eq $ExpectedProcessId `
		-and $listeners[0].LocalAddress -eq '127.0.0.1')
}

function Get-FreeLoopbackPort {
	$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
	try {
		$listener.Start()
		return [int]$listener.LocalEndpoint.Port
	} finally {
		$listener.Stop()
	}
}

function Invoke-CapturedProcess {
	param(
		[Parameter(Mandatory)][string]$FileName,
		[Parameter(Mandatory)][string[]]$Arguments
	)

	$processInfo = New-Object System.Diagnostics.ProcessStartInfo
	$processInfo.FileName = $FileName
	$processInfo.Arguments = (($Arguments | ForEach-Object { Quote-ProcessArgument ([string]$_) }) -join ' ')
	$processInfo.UseShellExecute = $false
	$processInfo.CreateNoWindow = $true
	$processInfo.RedirectStandardOutput = $true
	$processInfo.RedirectStandardError = $true
	$process = [System.Diagnostics.Process]::Start($processInfo)
	$stdoutTask = $process.StandardOutput.ReadToEndAsync()
	$stderrTask = $process.StandardError.ReadToEndAsync()
	$process.WaitForExit()
	$result = [PSCustomObject]@{
		ExitCode = $process.ExitCode
		Stdout = $stdoutTask.GetAwaiter().GetResult()
		Stderr = $stderrTask.GetAwaiter().GetResult()
	}
	$process.Dispose()
	return $result
}

function New-RandomBase64Secret {
	$bytes = New-Object byte[] 48
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
		$output = @(& $dockerPath @Arguments 2>&1)
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
	param([Parameter(Mandatory)][string]$Sql)

	$result = Invoke-DockerCommand -Environment @{ MYSQL_PWD = $mysqlRootPassword } -Arguments @(
		'exec', '--env', 'MYSQL_PWD', $containerName, 'mysql',
		'--batch', '--skip-column-names', '--user=root', $databaseName, "--execute=$Sql"
	)
	if ($result.ExitCode -ne 0) {
		throw 'A query against the disposable audit database failed.'
	}
	return (($result.Output | ForEach-Object { [string]$_ }) -join "`n").Trim()
}

function Get-DatabaseIntegrity {
	$brokenReferences = [int](Invoke-DbQuery @'
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
'@)
	$unbalancedExpenses = [int](Invoke-DbQuery @'
SELECT COUNT(*)
FROM expenses e
LEFT JOIN (
  SELECT expense_id, SUM(amount) AS split_total
  FROM expense_splits
  GROUP BY expense_id
) s ON s.expense_id = e.id
WHERE s.expense_id IS NULL OR e.amount <> s.split_total
'@)
	$nonMemberSplits = [int](Invoke-DbQuery @'
SELECT COUNT(*)
FROM expense_splits s
JOIN expenses e ON e.id = s.expense_id
LEFT JOIN group_members m ON m.group_id = e.group_id AND m.user_id = s.user_id
WHERE m.id IS NULL
'@)
	$nonMemberSettlements = [int](Invoke-DbQuery @'
SELECT COUNT(*)
FROM settlements s
LEFT JOIN group_members sender ON sender.group_id = s.group_id AND sender.user_id = s.from_user_id
LEFT JOIN group_members receiver ON receiver.group_id = s.group_id AND receiver.user_id = s.to_user_id
WHERE sender.id IS NULL OR receiver.id IS NULL
'@)
	$unbalancedGroups = [int](Invoke-DbQuery @'
SELECT COUNT(*)
FROM (
  SELECT g.id,
    COALESCE(p.total, 0) - COALESCE(s.total, 0)
      + COALESCE(r.total, 0) - COALESCE(t.total, 0) AS net_total
  FROM expense_groups g
  LEFT JOIN (SELECT group_id, SUM(amount) AS total FROM expenses GROUP BY group_id) p
    ON p.group_id = g.id
  LEFT JOIN (
    SELECT e.group_id, SUM(s.amount) AS total
    FROM expenses e JOIN expense_splits s ON s.expense_id = e.id
    GROUP BY e.group_id
  ) s ON s.group_id = g.id
  LEFT JOIN (SELECT group_id, SUM(amount) AS total FROM settlements GROUP BY group_id) r
    ON r.group_id = g.id
  LEFT JOIN (SELECT group_id, SUM(amount) AS total FROM settlements GROUP BY group_id) t
    ON t.group_id = g.id
) ledger
WHERE net_total <> 0
'@)
	$duplicateRequestIds = [int](Invoke-DbQuery @'
SELECT COUNT(*)
FROM (
  SELECT group_id, request_id
  FROM settlements
  GROUP BY group_id, request_id
  HAVING COUNT(*) > 1
) duplicates
'@)
	return [PSCustomObject]@{
		BrokenReferences = $brokenReferences
		UnbalancedExpenses = $unbalancedExpenses
		NonMemberSplits = $nonMemberSplits
		NonMemberSettlements = $nonMemberSettlements
		UnbalancedGroupLedgers = $unbalancedGroups
		DuplicateSettlementRequestIds = $duplicateRequestIds
	}
}

function ConvertTo-RouteTemplate {
	param([string]$Path)

	$route = ([Uri]("$apiUrl$Path")).AbsolutePath
	$route = [regex]::Replace($route, '^/api/groups/\d+', '/api/groups/{groupId}')
	$route = [regex]::Replace($route, '/expenses/\d+', '/expenses/{expenseId}')
	return $route
}

function Invoke-AuditRequest {
	param(
		[Parameter(Mandatory)][ValidateSet('GET', 'POST', 'PUT', 'DELETE')][string]$Method,
		[Parameter(Mandatory)][string]$Path,
		[string]$Token,
		[object]$Body,
		[Parameter(Mandatory)][string]$Label,
		[Parameter(Mandatory)][int[]]$ExpectedStatus
	)

	$watch = [System.Diagnostics.Stopwatch]::StartNew()
	$startedAtMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
	$headers = @{ Accept = 'application/json' }
	$invokeArguments = @{
		Uri = "$apiUrl$Path"
		Method = $Method
		Headers = $headers
		UseBasicParsing = $true
		TimeoutSec = 15
		ErrorAction = 'Stop'
	}
	if (-not [string]::IsNullOrWhiteSpace($Token)) {
		$headers.Authorization = "Bearer $Token"
	}
	if ($null -ne $Body) {
		$invokeArguments.ContentType = 'application/json'
		$invokeArguments.Body = ConvertTo-Json -InputObject $Body -Depth 12 -Compress
	}
	$responseText = ''
	$status = 0
	try {
		$response = Invoke-WebRequest @invokeArguments
		$status = [int]$response.StatusCode
		$responseText = [string]$response.Content
	} catch {
		$webResponse = $_.Exception.Response
		if ($null -eq $webResponse) {
			throw "$Label failed before receiving an HTTP response."
		}
		$status = [int]$webResponse.StatusCode
		$streamReader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
		try {
			$responseText = $streamReader.ReadToEnd()
		} finally {
			$streamReader.Dispose()
			$webResponse.Dispose()
		}
	}
	$watch.Stop()
	$parsedBody = $null
	if (-not [string]::IsNullOrWhiteSpace($responseText)) {
		try {
			$parsedBody = ConvertFrom-Json -InputObject $responseText
		} catch {
			throw "$Label returned a non-JSON response."
		}
	}
	$requestRecords.Add([PSCustomObject]@{
		Label = $Label
		Method = $Method
		Route = ConvertTo-RouteTemplate -Path $Path
		StartedAtMs = $startedAtMs
		ClientDurationMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 3)
		Status = $status
		Mode = $captureMode
	})
	if ($ExpectedStatus -notcontains $status) {
		throw "$Label expected HTTP $($ExpectedStatus -join '/') but received $status."
	}
	return [PSCustomObject]@{
		Status = $status
		Body = $parsedBody
		ClientDurationMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 3)
		StartedAtMs = $startedAtMs
	}
}

function Wait-ApiReady {
	$ready = $false
	for ($attempt = 0; $attempt -lt 60; $attempt++) {
		if ($null -eq $script:apiProcess -or $script:apiProcess.HasExited) {
			$exitCode = if ($null -ne $script:apiProcess) { $script:apiProcess.ExitCode } else { 'unknown' }
			$startupOutput = $script:apiStdoutTask.GetAwaiter().GetResult() +
				$script:apiStderrTask.GetAwaiter().GetResult()
			$startupTail = Get-RedactedProcessTail -RawText $startupOutput
			throw "The isolated API process exited with code $exitCode before becoming ready in $script:apiMode mode. $startupTail"
		}
		try {
			$response = Invoke-WebRequest -UseBasicParsing -Uri "$apiUrl/api/health" -TimeoutSec 3 -ErrorAction Stop
			if ([int]$response.StatusCode -eq 200) {
				$ready = $true
				break
			}
		} catch {
			Start-Sleep -Seconds 1
		}
		Start-Sleep -Seconds 1
	}
	if (-not $ready) {
		throw "The isolated API did not become ready in $apiMode mode."
	}
	if (-not (Test-LoopbackListener -Port $apiPort -ExpectedProcessId ([string]$script:apiProcess.Id))) {
		throw 'The audit API listener was not owned exclusively by the expected loopback-bound process.'
	}
}

function Start-AuditApi {
	param(
		[Parameter(Mandatory)][ValidateSet('console', 'otlp', 'none', 'baseline')][string]$Mode
	)

	if (@(Get-ListenerDetails -Port $apiPort).Count -ne 0) {
		throw "Refusing to start the audit backend because port $apiPort is already in use."
	}
	$script:apiMode = $Mode
	$script:captureMode = $Mode
	$processInfo = New-Object System.Diagnostics.ProcessStartInfo
	$processInfo.FileName = $javaPath
	$processInfo.WorkingDirectory = $backendRoot
	if ($Mode -eq 'baseline') {
		$processInfo.Arguments = "-jar `"$jarPath`" --server.address=127.0.0.1 --server.port=$apiPort"
	} else {
		$processInfo.Arguments = "-javaagent:`"$agentPath`" -jar `"$jarPath`" --server.address=127.0.0.1 --server.port=$apiPort"
	}
	$processInfo.UseShellExecute = $false
	$processInfo.CreateNoWindow = $true
	$processInfo.RedirectStandardOutput = $true
	$processInfo.RedirectStandardError = $true
	$environment = $processInfo.EnvironmentVariables
	$environment['DB_URL'] = "jdbc:mysql://127.0.0.1:${databasePort}/${databaseName}?allowPublicKeyRetrieval=true"
	$environment['DB_USERNAME'] = $databaseUser
	$environment['DB_PASSWORD'] = $mysqlPassword
	$environment['JWT_SECRET'] = $jwtSecret
	$environment['APP_CORS_ALLOWED_ORIGINS'] = 'http://127.0.0.1:5173'
	foreach ($name in @(
			'OTEL_SERVICE_NAME',
			'OTEL_TRACES_EXPORTER',
			'OTEL_METRICS_EXPORTER',
			'OTEL_LOGS_EXPORTER',
			'OTEL_EXPORTER_OTLP_ENDPOINT',
			'OTEL_EXPORTER_OTLP_PROTOCOL',
			'OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED',
			'OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_REQUEST_HEADERS',
			'OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_REQUEST_HEADERS',
			'OTEL_INSTRUMENTATION_HTTP_SERVER_CAPTURE_RESPONSE_HEADERS',
			'OTEL_INSTRUMENTATION_HTTP_CLIENT_CAPTURE_RESPONSE_HEADERS'
		)) {
		$environment.Remove($name)
	}
	if ($Mode -ne 'baseline') {
		$environment['OTEL_SERVICE_NAME'] = 'splitsmart-audit'
		$environment['OTEL_METRICS_EXPORTER'] = 'none'
		$environment['OTEL_LOGS_EXPORTER'] = 'none'
		$environment['OTEL_INSTRUMENTATION_COMMON_DB_STATEMENT_SANITIZER_ENABLED'] = 'true'
	}
	if ($Mode -eq 'console') {
		$environment['OTEL_TRACES_EXPORTER'] = 'console'
	} elseif ($Mode -eq 'otlp') {
		$environment['OTEL_TRACES_EXPORTER'] = 'otlp'
		$environment['OTEL_EXPORTER_OTLP_ENDPOINT'] = "http://127.0.0.1:$collectorPort"
		$environment['OTEL_EXPORTER_OTLP_PROTOCOL'] = 'grpc'
	} elseif ($Mode -eq 'none') {
		$environment['OTEL_TRACES_EXPORTER'] = 'none'
	}
	$script:apiProcess = [System.Diagnostics.Process]::Start($processInfo)
	$script:apiStdoutTask = $script:apiProcess.StandardOutput.ReadToEndAsync()
	$script:apiStderrTask = $script:apiProcess.StandardError.ReadToEndAsync()
	Wait-ApiReady
}

function Stop-AuditApi {
	if ($null -eq $script:apiProcess) {
		return [PSCustomObject]@{ Stdout = ''; Stderr = ''; ExitCode = $null }
	}
	if (-not $script:apiProcess.HasExited) {
		Start-Sleep -Seconds 6
		$listeners = @(Get-ListenerDetails -Port $apiPort)
		if ($listeners.Count -gt 0 -and -not (Test-LoopbackListener -Port $apiPort -ExpectedProcessId ([string]$script:apiProcess.Id))) {
			throw "Refused to stop an API listener not owned by PID $($script:apiProcess.Id)."
		}
		Stop-Process -Id $script:apiProcess.Id -Force
		$script:apiProcess.WaitForExit(15000) | Out-Null
	}
	$stdout = $script:apiStdoutTask.GetAwaiter().GetResult()
	$stderr = $script:apiStderrTask.GetAwaiter().GetResult()
	$exitCode = $script:apiProcess.ExitCode
	$script:apiProcess.Dispose()
	$script:apiProcess = $null
	$script:apiStdoutTask = $null
	$script:apiStderrTask = $null
	$script:apiMode = 'none'
	return [PSCustomObject]@{
		Stdout = [string]$stdout
		Stderr = [string]$stderr
		ExitCode = $exitCode
	}
}

function Start-AuditCollector {
	$script:collectorPort = Get-FreeLoopbackPort
	if (@(Get-ListenerDetails -Port $script:collectorPort).Count -ne 0) {
		throw 'The selected local collector port was claimed before startup.'
	}
	@"
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 127.0.0.1:$script:collectorPort
exporters:
  debug:
    verbosity: detailed
service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [debug]
"@ | Set-Content -LiteralPath $collectorConfigPath -Encoding ASCII
	$processInfo = New-Object System.Diagnostics.ProcessStartInfo
	$processInfo.FileName = $collectorPath
	$processInfo.WorkingDirectory = $reportRoot
	$processInfo.Arguments = "--config `"$collectorConfigPath`""
	$processInfo.UseShellExecute = $false
	$processInfo.CreateNoWindow = $true
	$processInfo.RedirectStandardOutput = $true
	$processInfo.RedirectStandardError = $true
	$script:collectorProcess = [System.Diagnostics.Process]::Start($processInfo)
	$script:collectorStdoutTask = $script:collectorProcess.StandardOutput.ReadToEndAsync()
	$script:collectorStderrTask = $script:collectorProcess.StandardError.ReadToEndAsync()
	$ready = $false
	for ($attempt = 0; $attempt -lt 30; $attempt++) {
		if ($script:collectorProcess.HasExited) {
			$collectorStartup = $script:collectorStdoutTask.GetAwaiter().GetResult() +
				$script:collectorStderrTask.GetAwaiter().GetResult()
			$startupTail = (($collectorStartup -split "`r?`n" | Select-Object -Last 10) -join ' ').Trim()
			throw "The local collector exited during startup: $startupTail"
		}
		if (Test-LoopbackListener -Port $script:collectorPort -ExpectedProcessId ([string]$script:collectorProcess.Id)) {
			$ready = $true
			break
		}
		Start-Sleep -Milliseconds 500
	}
	if (-not $ready) {
		throw 'The local collector did not open its expected loopback-only OTLP receiver.'
	}
}

function Stop-AuditCollector {
	if ($null -eq $script:collectorProcess) {
		return [PSCustomObject]@{ Stdout = ''; Stderr = ''; ExitCode = $null }
	}
	if (-not $script:collectorProcess.HasExited) {
		$listeners = @(Get-ListenerDetails -Port $script:collectorPort)
		if ($listeners.Count -gt 0 -and -not (Test-LoopbackListener -Port $script:collectorPort -ExpectedProcessId ([string]$script:collectorProcess.Id))) {
			throw "Refused to stop a collector listener not owned by PID $($script:collectorProcess.Id)."
		}
		Stop-Process -Id $script:collectorProcess.Id -Force
		$script:collectorProcess.WaitForExit(15000) | Out-Null
	}
	$stdout = $script:collectorStdoutTask.GetAwaiter().GetResult()
	$stderr = $script:collectorStderrTask.GetAwaiter().GetResult()
	$exitCode = $script:collectorProcess.ExitCode
	$script:collectorProcess.Dispose()
	$script:collectorProcess = $null
	$script:collectorStdoutTask = $null
	$script:collectorStderrTask = $null
	return [PSCustomObject]@{
		Stdout = [string]$stdout
		Stderr = [string]$stderr
		ExitCode = $exitCode
	}
}

function Get-RawSecurityInspection {
	param([string]$RawText)

	$knownSecretMatches = 0
	foreach ($secret in @($mysqlPassword, $mysqlRootPassword, $jwtSecret, $testPassword)) {
		if (-not [string]::IsNullOrEmpty($secret) -and $RawText.Contains($secret)) {
			$knownSecretMatches++
		}
	}
	$tokenMatches = 0
	foreach ($token in @($script:accounts.Values | ForEach-Object { $_.Token })) {
		if (-not [string]::IsNullOrEmpty([string]$token) -and $RawText.Contains([string]$token)) {
			$tokenMatches++
		}
	}
	$emailMatches = [regex]::Matches($RawText, '(?i)\botel-[a-z0-9-]+@example\.invalid\b').Count
	$authorizationCaptures = [regex]::Matches($RawText, '(?i)authorization\s*[:=]\s*bearer\s+[a-z0-9._-]+').Count
	return [PSCustomObject]@{
		KnownSecretMatches = $knownSecretMatches
		BearerTokenMatches = $tokenMatches
		AuthorizationHeaderCaptures = $authorizationCaptures
		SyntheticEmailMatches = $emailMatches
		RawTelemetryRetained = $false
	}
}

function Get-RedactedProcessTail {
	param([string]$RawText)

	$safeText = [string]$RawText
	foreach ($secret in @($mysqlPassword, $mysqlRootPassword, $jwtSecret, $testPassword, $databaseUser, $databaseName)) {
		if (-not [string]::IsNullOrEmpty($secret)) {
			$safeText = $safeText.Replace($secret, '[REDACTED]')
		}
	}
	if ($null -ne $accounts) {
		foreach ($account in $accounts.Values) {
			foreach ($sensitiveValue in @($account.Token, $account.Email)) {
				if (-not [string]::IsNullOrWhiteSpace([string]$sensitiveValue)) {
					$safeText = $safeText.Replace([string]$sensitiveValue, '[REDACTED]')
				}
			}
		}
	}
	$lines = @(
		$safeText -split "`r?`n" |
			Where-Object {
				-not [string]::IsNullOrWhiteSpace($_) -and
				$_ -notmatch '(?i)otel\.javaagent|LoggingSpanExporter|Span #|db\.statement|AttributesMap|TraceId|trace_id'
			} |
			Select-Object -Last 12
	)
	if ($lines.Count -eq 0) {
		return 'No non-telemetry application startup diagnostics were captured.'
	}
	return (($lines -join ' ') -replace '\s{2,}', ' ').Trim()
}

function Get-ConsoleTraceEvidence {
	param([string]$RawText)

	$spanMatches = [regex]::Matches(
		$RawText,
		"(?m)'(?<name>[^']+)'\s*:\s*(?<trace>[0-9a-fA-F]{32})\s+(?<span>[0-9a-fA-F]{16})\s+(?<kind>SERVER|CLIENT|INTERNAL)\b(?<attrs>[^\r\n]*)")
	$spans = @(
		foreach ($match in $spanMatches) {
			[PSCustomObject]@{
				Name = $match.Groups['name'].Value
				TraceId = $match.Groups['trace'].Value.ToLowerInvariant()
				SpanId = $match.Groups['span'].Value.ToLowerInvariant()
				Kind = $match.Groups['kind'].Value.ToUpperInvariant()
				Attributes = $match.Groups['attrs'].Value
			}
		}
	)
	$httpSpans = @($spans | Where-Object { $_.Kind -eq 'SERVER' })
	$databaseSpans = @(
		$spans | Where-Object {
			$_.Attributes -match '(?i)db\.system(?:\.name)?\s*=' -or
			$_.Attributes -match '(?i)db\.statement\s*='
		}
	)
	$httpTraceIds = @($httpSpans | ForEach-Object { $_.TraceId } | Sort-Object -Unique)
	$databaseTraceIds = @($databaseSpans | ForEach-Object { $_.TraceId } | Sort-Object -Unique)
	$sharedTraceIds = @($httpTraceIds | Where-Object { $databaseTraceIds -contains $_ })
	if ($httpSpans.Count -eq 0 -or $databaseSpans.Count -eq 0 -or $sharedTraceIds.Count -eq 0) {
		throw 'The console-exporter smoke pass did not prove real HTTP and JDBC spans in a shared trace.'
	}
	return [PSCustomObject]@{
		Exporter = 'console'
		HttpServerSpanCount = $httpSpans.Count
		JdbcSpanCount = $databaseSpans.Count
		SharedTraceCount = $sharedTraceIds.Count
		SampleTraceId = $sharedTraceIds[0]
		ParentIdsAndTimingsAvailable = $false
	}
}

function Get-SpanField {
	param(
		[string]$Text,
		[string]$Pattern
	)

	$match = [regex]::Match($Text, $Pattern)
	if ($match.Success) {
		return $match.Groups[1].Value.Trim()
	}
	return ''
}

function ConvertTo-UnixMilliseconds {
	param([string]$Timestamp)

	$value = $Timestamp.Trim() -replace ' UTC$', ''
	$value = [regex]::Replace($value, '(\.\d{7})\d+', '$1')
	$value = [regex]::Replace($value, '([+-]\d{2})(\d{2})$', '$1:$2')
	$parsed = [DateTimeOffset]::Parse(
		$value,
		[Globalization.CultureInfo]::InvariantCulture,
		[Globalization.DateTimeStyles]::AssumeUniversal)
	return $parsed.ToUnixTimeMilliseconds()
}

function Get-AttributeMap {
	param([string]$Block)

	$attributes = @{}
	foreach ($match in [regex]::Matches($Block, '(?m)^\s*->\s*(?<key>[^:]+):\s*(?<value>.*)$')) {
		$key = $match.Groups['key'].Value.Trim()
		$value = $match.Groups['value'].Value.Trim()
		$value = [regex]::Replace($value, '^(?:Str|Int|Bool|Double|Slice|Map)\((.*)\)$', '$1')
		$attributes[$key] = $value
	}
	return $attributes
}

function Get-DatabaseOperation {
	param(
		[string]$Name,
		[hashtable]$Attributes
	)

	$statement = ''
	foreach ($key in @('db.statement', 'db.query.text', 'db.query')) {
		if ($Attributes.ContainsKey($key)) {
			$statement = [string]$Attributes[$key]
			break
		}
	}
	$operation = ''
	$table = ''
	$operationMatch = [regex]::Match($statement, '(?i)^\s*(SELECT|INSERT|UPDATE|DELETE|CALL|REPLACE|ALTER|CREATE|DROP)\b')
	if ($operationMatch.Success) {
		$operation = $operationMatch.Groups[1].Value.ToUpperInvariant()
		$tableMatch = [regex]::Match($statement, '(?i)\b(?:FROM|JOIN|INTO|UPDATE)\s+[`"]?(?<table>[a-z0-9_]+)')
		if ($tableMatch.Success) {
			$table = $tableMatch.Groups['table'].Value.ToLowerInvariant()
		}
	} elseif ($Attributes.ContainsKey('db.operation')) {
		$operation = ([string]$Attributes['db.operation']).ToUpperInvariant()
	} elseif ($Attributes.ContainsKey('db.operation.name')) {
		$operation = ([string]$Attributes['db.operation.name']).ToUpperInvariant()
	} else {
		$operationMatch = [regex]::Match($Name, '(?i)^\s*(SELECT|INSERT|UPDATE|DELETE|CALL|REPLACE)\b')
		if ($operationMatch.Success) {
			$operation = $operationMatch.Groups[1].Value.ToUpperInvariant()
		}
	}
	$category = if ($table) { "$operation $table" } elseif ($operation) { $operation } else { 'JDBC operation' }
	return [PSCustomObject]@{
		Category = $category
		HasForUpdate = ($statement -match '(?i)\bFOR\s+UPDATE\b')
	}
}

function Get-DetailedSpans {
	param([string]$RawText)

	$spans = [System.Collections.Generic.List[object]]::new()
	$spanBlocks = [regex]::Matches(
		$RawText,
		'(?ms)^Span #\d+\r?\n(?<body>.*?)(?=^Span #\d+|^ScopeSpans #\d+|^ResourceSpans #\d+|\z)')
	foreach ($blockMatch in $spanBlocks) {
		$block = $blockMatch.Groups['body'].Value
		$traceId = Get-SpanField -Text $block -Pattern '(?m)^\s*Trace ID\s*:\s*([0-9a-fA-F]{32})\s*$'
		$spanId = Get-SpanField -Text $block -Pattern '(?m)^\s*ID\s*:\s*([0-9a-fA-F]{16})\s*$'
		$parentId = Get-SpanField -Text $block -Pattern '(?m)^\s*Parent ID\s*:\s*([0-9a-fA-F]{16})\s*$'
		$name = Get-SpanField -Text $block -Pattern '(?m)^\s*Name\s*:\s*(.+?)\s*$'
		$kind = Get-SpanField -Text $block -Pattern '(?m)^\s*Kind\s*:\s*(\S+)\s*$'
		$startText = Get-SpanField -Text $block -Pattern '(?m)^\s*Start time\s*:\s*(.+?)\s*$'
		$endText = Get-SpanField -Text $block -Pattern '(?m)^\s*End time\s*:\s*(.+?)\s*$'
		if ($traceId -notmatch '^[0-9a-fA-F]{32}$' -or
			$spanId -notmatch '^[0-9a-fA-F]{16}$' -or
			[string]::IsNullOrWhiteSpace($name) -or
			[string]::IsNullOrWhiteSpace($kind) -or
			[string]::IsNullOrWhiteSpace($startText) -or
			[string]::IsNullOrWhiteSpace($endText)) {
			continue
		}
		$attributes = Get-AttributeMap -Block $block
		$durationMs = [math]::Round(
			(ConvertTo-UnixMilliseconds -Timestamp $endText) -
			(ConvertTo-UnixMilliseconds -Timestamp $startText),
			3)
		$status = Get-SpanField -Text $block -Pattern '(?m)^\s*Status code\s*:\s*(.+?)\s*$'
		$httpCode = ''
		foreach ($key in @('http.response.status_code', 'http.status_code')) {
			if ($attributes.ContainsKey($key)) {
				$httpCode = [string]$attributes[$key]
				break
			}
		}
		$isDatabase = $false
		foreach ($key in @('db.system', 'db.system.name', 'db.statement', 'db.query.text', 'db.operation')) {
			if ($attributes.ContainsKey($key)) {
				$isDatabase = $true
				break
			}
		}
		$operation = Get-DatabaseOperation -Name $name -Attributes $attributes
		$route = ''
		foreach ($key in @('http.route', 'url.path', 'http.target')) {
			if ($attributes.ContainsKey($key)) {
				$route = [string]$attributes[$key]
				break
			}
		}
		$method = ''
		foreach ($key in @('http.request.method', 'http.method')) {
			if ($attributes.ContainsKey($key)) {
				$method = [string]$attributes[$key]
				break
			}
		}
		$spans.Add([PSCustomObject]@{
			TraceId = $traceId.ToLowerInvariant()
			SpanId = $spanId.ToLowerInvariant()
			ParentSpanId = $parentId.ToLowerInvariant()
			Name = $name
			Kind = $kind
			StartUnixMs = ConvertTo-UnixMilliseconds -Timestamp $startText
			EndUnixMs = ConvertTo-UnixMilliseconds -Timestamp $endText
			DurationMs = $durationMs
			Status = $status
			HttpStatus = $httpCode
			Route = $route
			Method = $method.ToUpperInvariant()
			IsDatabase = $isDatabase
			DatabaseCategory = $operation.Category
			HasForUpdate = $operation.HasForUpdate
			Attributes = $attributes
		})
	}
	return @($spans)
}

function Test-SpanDescendsFrom {
	param(
		[object]$Span,
		[object]$Root,
		[hashtable]$SpanIndex
	)

	$parentId = $Span.ParentSpanId
	for ($depth = 0; $depth -lt 32 -and $parentId; $depth++) {
		if ($parentId -eq $Root.SpanId) {
			return $true
		}
		$key = "$($Span.TraceId)|$parentId"
		if (-not $SpanIndex.ContainsKey($key)) {
			return $false
		}
		$parentId = $SpanIndex[$key].ParentSpanId
	}
	return $false
}

function Get-RequestTraceObservations {
	param([object[]]$Spans)

	$spanIndex = @{}
	foreach ($span in $Spans) {
		$spanIndex["$($span.TraceId)|$($span.SpanId)"] = $span
	}
	$roots = @(
		$Spans | Where-Object {
			$_.Kind -match '^(?i:SERVER)$' -and
			-not [string]::IsNullOrWhiteSpace($_.Method) -and
			-not [string]::IsNullOrWhiteSpace($_.Route)
		}
	)
	$usedRoots = @{}
	$observations = [System.Collections.Generic.List[object]]::new()
	foreach ($requestRecord in @($requestRecords | Where-Object { $_.Mode -eq 'otlp' })) {
		$candidates = @(
			$roots | Where-Object {
				-not $usedRoots.ContainsKey($_.SpanId) -and
				$_.Method -eq $requestRecord.Method -and
				$_.Route -eq $requestRecord.Route
			} | Sort-Object { [math]::Abs($_.StartUnixMs - $requestRecord.StartedAtMs) }
		)
		if ($candidates.Count -eq 0) {
			continue
		}
		$root = $candidates[0]
		$usedRoots[$root.SpanId] = $true
		$databaseSpans = @(
			$Spans | Where-Object {
				$_.TraceId -eq $root.TraceId -and
				$_.IsDatabase -and
				(Test-SpanDescendsFrom -Span $_ -Root $root -SpanIndex $spanIndex)
			}
		)
		$unlinkedDatabaseSpans = @(
			$Spans | Where-Object {
				$_.TraceId -eq $root.TraceId -and
				$_.IsDatabase -and
				-not (Test-SpanDescendsFrom -Span $_ -Root $root -SpanIndex $spanIndex)
			}
		)
		$operationCounts = @(
			$databaseSpans |
				Group-Object DatabaseCategory |
				Sort-Object Name |
				ForEach-Object {
					[PSCustomObject]@{ Operation = $_.Name; Count = $_.Count }
				}
		)
		$sampleChildren = @(
			$databaseSpans |
				Select-Object -First 4 |
				ForEach-Object {
					[PSCustomObject]@{
						SpanId = $_.SpanId
						ParentSpanId = $_.ParentSpanId
						Operation = $_.DatabaseCategory
						DurationMs = $_.DurationMs
						ParentChainVerified = $true
					}
				}
		)
		$observations.Add([PSCustomObject]@{
			Label = $requestRecord.Label
			Route = $requestRecord.Route
			Method = $requestRecord.Method
			ClientDurationMs = $requestRecord.ClientDurationMs
			ServerDurationMs = $root.DurationMs
			HttpStatus = $root.HttpStatus
			TraceId = $root.TraceId
			ServerSpanId = $root.SpanId
			ServerParentSpanId = $root.ParentSpanId
			DatabaseSpanCount = $databaseSpans.Count
			DatabaseDurationMs = [math]::Round(($databaseSpans | Measure-Object -Property DurationMs -Sum).Sum, 3)
			DatabaseOperations = $operationCounts
			ParentChildVerified = ($databaseSpans.Count -gt 0 -and $unlinkedDatabaseSpans.Count -eq 0)
			UnlinkedDatabaseSpanCount = $unlinkedDatabaseSpans.Count
			SampleDatabaseChildren = $sampleChildren
			HasForUpdateSpan = [bool](@($databaseSpans | Where-Object { $_.HasForUpdate }).Count -gt 0)
		})
	}
	return @($observations)
}

function ConvertTo-AuditDecimal {
	param([object]$Value)

	return [decimal]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture)
}

function New-TestAccount {
	param([string]$Role)

	$email = "otel-$runId-$Role@example.invalid"
	$registration = Invoke-AuditRequest -Method POST -Path '/api/auth/register' -Body @{
		name = "OTel Audit $Role"
		email = $email
		password = $testPassword
	} -Label "register-$Role" -ExpectedStatus @(201)
	$login = Invoke-AuditRequest -Method POST -Path '/api/auth/login' -Body @{
		email = $email
		password = $testPassword
	} -Label "login-$Role" -ExpectedStatus @(200)
	return [PSCustomObject]@{
		Id = [long]$registration.Body.id
		Email = $email
		Token = [string]$login.Body.accessToken
	}
}

function New-TestGroup {
	param(
		[object]$Owner,
		[object]$Debtor,
		[object]$Creditor,
		[string]$Label,
		[switch]$AddOutsider
	)

	$created = Invoke-AuditRequest -Method POST -Path '/api/groups' -Token $Owner.Token -Body @{
		name = "OTel $Label $runId"
	} -Label "$Label-create-group" -ExpectedStatus @(201)
	$groupId = [long]$created.Body.id
	[void](Invoke-AuditRequest -Method POST -Path "/api/groups/$groupId/members" -Token $Owner.Token -Body @{
		email = $Debtor.Email
	} -Label "$Label-add-debtor" -ExpectedStatus @(201))
	[void](Invoke-AuditRequest -Method POST -Path "/api/groups/$groupId/members" -Token $Owner.Token -Body @{
		email = $Creditor.Email
	} -Label "$Label-add-creditor" -ExpectedStatus @(201))
	if ($AddOutsider) {
		[void](Invoke-AuditRequest -Method POST -Path "/api/groups/$groupId/members" -Token $Owner.Token -Body @{
			email = $accounts.outsider.Email
		} -Label "$Label-add-outsider" -ExpectedStatus @(201))
	}
	return $groupId
}

function New-EqualExpenseBody {
	param(
		[string]$Description,
		[string]$Amount,
		[object]$Owner,
		[object]$Debtor,
		[object]$Creditor
	)

	return @{
		description = $Description
		amount = $Amount
		paidByUserId = $Owner.Id
		currencyCode = 'INR'
		splitType = 'EQUAL'
		participantUserIds = @($Owner.Id, $Debtor.Id, $Creditor.Id)
	}
}

function New-ExactExpenseBody {
	param(
		[string]$Description,
		[string]$Amount,
		[string[]]$Amounts,
		[object]$Owner,
		[object]$Debtor,
		[object]$Creditor
	)

	return @{
		description = $Description
		amount = $Amount
		paidByUserId = $Owner.Id
		currencyCode = 'INR'
		splitType = 'EXACT'
		splits = @(
			@{ userId = $Owner.Id; amount = $Amounts[0] },
			@{ userId = $Debtor.Id; amount = $Amounts[1] },
			@{ userId = $Creditor.Id; amount = $Amounts[2] }
		)
	}
}

function Measure-ReadWorkload {
	param(
		[Parameter(Mandatory)][ValidateSet('baseline', 'none', 'console')][string]$Mode,
		[Parameter(Mandatory)][long]$GroupId,
		[Parameter(Mandatory)][string]$Token,
		[int]$Warmup = 10,
		[int]$RequestCount = 60
	)

	Start-AuditApi -Mode $Mode
	for ($index = 0; $index -lt $Warmup; $index++) {
		$response = Invoke-AuditRequest -Method GET -Path "/api/groups/$GroupId/balances" -Token $Token `
			-Label "$Mode-warmup" -ExpectedStatus @(200)
		if ($null -eq $response.Body.balances) {
			throw "The $Mode warm-up returned no balance data."
		}
	}
	Start-Sleep -Seconds 1
	$apiProcess.Refresh()
	$cpuBefore = $apiProcess.TotalProcessorTime.TotalMilliseconds
	$memoryPeak = [double]$apiProcess.WorkingSet64
	$durations = [System.Collections.Generic.List[double]]::new()
	$watch = [System.Diagnostics.Stopwatch]::StartNew()
	for ($index = 0; $index -lt $RequestCount; $index++) {
		$response = Invoke-AuditRequest -Method GET -Path "/api/groups/$GroupId/balances" -Token $Token `
			-Label "$Mode-measured" -ExpectedStatus @(200)
		$durations.Add([double]$response.ClientDurationMs)
		$apiProcess.Refresh()
		$memoryPeak = [math]::Max($memoryPeak, [double]$apiProcess.WorkingSet64)
	}
	$watch.Stop()
	$apiProcess.Refresh()
	$cpuAfter = $apiProcess.TotalProcessorTime.TotalMilliseconds
	$orderedDurations = @($durations | Sort-Object)
	$p50Index = [math]::Max(0, [math]::Ceiling($RequestCount * 0.50) - 1)
	$p95Index = [math]::Max(0, [math]::Ceiling($RequestCount * 0.95) - 1)
	$performance = [PSCustomObject]@{
		Mode = $Mode
		VirtualUsers = 1
		WarmupRequests = $Warmup
		MeasuredRequests = $RequestCount
		UnexpectedErrors = 0
		P50Ms = [math]::Round($orderedDurations[$p50Index], 3)
		P95Ms = [math]::Round($orderedDurations[$p95Index], 3)
		RequestsPerSecond = [math]::Round($RequestCount / $watch.Elapsed.TotalSeconds, 3)
		ElapsedSeconds = [math]::Round($watch.Elapsed.TotalSeconds, 3)
		ProcessCpuMs = [math]::Round($cpuAfter - $cpuBefore, 3)
		ProcessWorkingSetPeakMiB = [math]::Round($memoryPeak / 1MB, 2)
		Exporter = if ($Mode -eq 'console') { 'console' } elseif ($Mode -eq 'none') { 'none' } else { 'none; agent absent for baseline' }
	}
	$processOutput = Stop-AuditApi
	return [PSCustomObject]@{
		Performance = $performance
		ProcessOutput = $processOutput
	}
}

function Quote-ProcessArgument {
	param([string]$Value)

	if ($Value -notmatch '[\s"]') {
		return $Value
	}
	$escaped = $Value.Replace('"', '\"')
	return '"' + $escaped + '"'
}

function Start-DatabaseLockHolder {
	param([long]$GroupId)

	$lockSql = "START TRANSACTION; SELECT id FROM expense_groups WHERE id = $GroupId FOR UPDATE; SELECT 'LOCK_HELD'; DO SLEEP(2); COMMIT;"
	$arguments = @(
		'exec', '--env', 'MYSQL_PWD', $containerName, 'mysql',
		'--batch', '--skip-column-names', '--unbuffered', '--user=root', $databaseName, "--execute=$lockSql"
	)
	$processInfo = New-Object System.Diagnostics.ProcessStartInfo
	$processInfo.FileName = $dockerPath
	$processInfo.Arguments = (($arguments | ForEach-Object { Quote-ProcessArgument ([string]$_) }) -join ' ')
	$processInfo.UseShellExecute = $false
	$processInfo.CreateNoWindow = $true
	$processInfo.RedirectStandardOutput = $true
	$processInfo.RedirectStandardError = $true
	$processInfo.EnvironmentVariables['MYSQL_PWD'] = $mysqlRootPassword
	$lockProcess = [System.Diagnostics.Process]::Start($processInfo)
	$script:LockProcess = $lockProcess
	$script:LockStderrTask = $lockProcess.StandardError.ReadToEndAsync()
	$groupLineTask = $lockProcess.StandardOutput.ReadLineAsync()
	if (-not $groupLineTask.Wait(10000)) {
		throw 'The disposable MySQL lock-holder did not confirm its locked group row.'
	}
	$groupLine = $groupLineTask.GetAwaiter().GetResult()
	$markerLineTask = $lockProcess.StandardOutput.ReadLineAsync()
	if (-not $markerLineTask.Wait(10000)) {
		throw 'The disposable MySQL lock-holder did not confirm its lock marker.'
	}
	$markerLine = $markerLineTask.GetAwaiter().GetResult()
	if ([string]$groupLine -ne [string]$GroupId -or [string]$markerLine -ne 'LOCK_HELD') {
		throw 'The disposable MySQL lock-holder returned an unexpected confirmation.'
	}
	return [PSCustomObject]@{
		Process = $lockProcess
		HeldAt = [DateTimeOffset]::UtcNow
	}
}

function Stop-DatabaseLockHolder {
	if ($null -eq $script:LockProcess) {
		return
	}
	if (-not $script:LockProcess.HasExited) {
		$script:LockProcess.WaitForExit(10000) | Out-Null
	}
	if (-not $script:LockProcess.HasExited) {
		Stop-Process -Id $script:LockProcess.Id -Force
		$script:LockProcess.WaitForExit(5000) | Out-Null
	}
	if ($script:LockProcess.HasExited -and $script:LockProcess.ExitCode -ne 0) {
		throw 'The disposable MySQL lock-holder did not complete successfully.'
	}
	$null = $script:LockProcess.StandardOutput.ReadToEndAsync().GetAwaiter().GetResult()
	$null = $script:LockStderrTask.GetAwaiter().GetResult()
	$script:LockProcess.Dispose()
	$script:LockProcess = $null
	$script:LockStderrTask = $null
}

function Assert-IsolatedPreflight {
	if (-not (Test-Path -LiteralPath $javaPath) -or -not (Test-Path -LiteralPath $jarPath)) {
		throw 'The Java 21 executable or packaged backend JAR is missing.'
	}
	if (-not (Test-Path -LiteralPath $agentPath) -or -not (Test-Path -LiteralPath $collectorPath)) {
		throw 'The checksum-verified local OpenTelemetry agent or collector is missing.'
	}
	if ($agentPath.StartsWith($repositoryRoot, [StringComparison]::OrdinalIgnoreCase) -or
		$collectorPath.StartsWith($repositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
		throw 'OpenTelemetry binaries must remain outside the repository.'
	}
	$javaResult = Invoke-CapturedProcess -FileName $javaPath -Arguments @('-version')
	$javaOutput = $javaResult.Stdout + $javaResult.Stderr
	if ($javaResult.ExitCode -ne 0 -or $javaOutput -notmatch 'version "21\.') {
		throw 'The audit requires the verified Java 21 runtime.'
	}
	$javaRuntimeMatch = [regex]::Match($javaOutput, 'version "([^"]+)"')
	if (-not $javaRuntimeMatch.Success) {
		throw 'Could not identify the audit Java runtime version.'
	}
	$script:JavaRuntimeVersion = $javaRuntimeMatch.Groups[1].Value
	$agentHash = (Get-FileHash -LiteralPath $agentPath -Algorithm SHA256).Hash.ToLowerInvariant()
	if ($agentHash -ne 'e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149') {
		throw 'The OpenTelemetry Java agent does not match the verified official release checksum.'
	}
	$agentVersionResult = Invoke-CapturedProcess -FileName $javaPath -Arguments @('-jar', $agentPath, '--version')
	$agentVersionOutput = $agentVersionResult.Stdout + $agentVersionResult.Stderr
	if ($agentVersionResult.ExitCode -ne 0 -or $agentVersionOutput -notmatch [regex]::Escape($agentVersion)) {
		throw 'The OpenTelemetry Java agent version could not be verified.'
	}
	$collectorVersionResult = Invoke-CapturedProcess -FileName $collectorPath -Arguments @('--version')
	$collectorVersionOutput = $collectorVersionResult.Stdout + $collectorVersionResult.Stderr
	if ($collectorVersionResult.ExitCode -ne 0 -or $collectorVersionOutput -notmatch [regex]::Escape($collectorVersion)) {
		throw 'The local OpenTelemetry Collector version could not be verified.'
	}
	$dockerInfo = (& $dockerPath info --format '{{.ServerVersion}}' 2>&1 | Out-String).Trim()
	if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($dockerInfo)) {
		throw 'The Docker daemon is not responsive.'
	}
	if ((Get-PSDrive C).Free -lt 2GB) {
		throw 'Insufficient free space for this bounded local audit.'
	}
	if (@(Get-ListenerDetails -Port $apiPort).Count -ne 0) {
		throw "Audit API port $apiPort is already occupied."
	}
	foreach ($port in @(3306, 8081)) {
		$before = @($initialListeners[[string]$port])
		if ($before.Count -eq 0) {
			throw "Expected protected listener on port $port was not present at preflight."
		}
	}
	$imageId = (& $dockerPath image inspect $image --format '{{.Id}}' 2>$null)
	if ($LASTEXITCODE -ne 0 -or [string]$imageId -ne $initialImageId) {
		throw 'The required cached MySQL image could not be verified.'
	}
}

New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null
$initialContainers = @(& $dockerPath ps --all --quiet --no-trunc | Sort-Object)
$initialVolumes = @(& $dockerPath volume ls --quiet | Sort-Object)
$initialImageId = ((& $dockerPath image inspect $image --format '{{.Id}}' 2>$null) | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
	throw 'The Docker daemon or cached MySQL image is unavailable.'
}
foreach ($port in @(3306, 8081, $apiPort)) {
	$initialListeners[[string]$port] = @(Get-ListenerDetails -Port $port)
}
$migrationDirectory = Join-Path $backendRoot 'src\main\resources\db\migration'
foreach ($migration in Get-ChildItem -LiteralPath $migrationDirectory -Filter 'V*.sql' -ErrorAction Stop) {
	$initialMigrationHashes[$migration.Name] = (Get-FileHash -LiteralPath $migration.FullName -Algorithm SHA256).Hash
}
if ($initialMigrationHashes.Count -lt 3) {
	throw 'Could not identify all Flyway migration files before the audit.'
}

try {
	Assert-IsolatedPreflight
	$volumeResult = Invoke-DockerCommand -Arguments @(
		'volume', 'create',
		'--label', 'com.splitsmart.audit.phase=6',
		'--label', "com.splitsmart.audit.run=$runId",
		$volumeName
	)
	if ($volumeResult.ExitCode -ne 0 -or
		(($volumeResult.Output | ForEach-Object { [string]$_ }) -join '').Trim() -ne $volumeName) {
		throw 'Could not create the owned disposable MySQL volume.'
	}
	$volumeCreated = $true
	$volumeLabels = ((& $dockerPath volume inspect --format '{{json .Labels}}' $volumeName) | ConvertFrom-Json)
	if ([string]$volumeLabels.'com.splitsmart.audit.phase' -ne '6' -or
		[string]$volumeLabels.'com.splitsmart.audit.run' -ne $runId) {
		throw 'Could not prove ownership of the disposable MySQL volume.'
	}
	$dockerEnvironment = @{
		MYSQL_DATABASE = $databaseName
		MYSQL_USER = $databaseUser
		MYSQL_PASSWORD = $mysqlPassword
		MYSQL_ROOT_PASSWORD = $mysqlRootPassword
	}
	$containerResult = Invoke-DockerCommand -Environment $dockerEnvironment -Arguments @(
		'run', '--detach',
		'--name', $containerName,
		'--label', 'com.splitsmart.audit.phase=6',
		'--label', "com.splitsmart.audit.run=$runId",
		'--publish', '127.0.0.1::3306',
		'--mount', "type=volume,source=$volumeName,target=/var/lib/mysql",
		'--env', 'MYSQL_DATABASE',
		'--env', 'MYSQL_USER',
		'--env', 'MYSQL_PASSWORD',
		'--env', 'MYSQL_ROOT_PASSWORD',
		$image
	)
	if ($containerResult.ExitCode -ne 0) {
		throw 'Could not start the disposable MySQL 8.4.13 container.'
	}
	$containerId = (($containerResult.Output | ForEach-Object { [string]$_ }) -join '').Trim()
	$inspectedId = ((& $dockerPath inspect --format '{{.Id}}' $containerName 2>$null) | Out-String).Trim()
	$containerLabels = ((& $dockerPath inspect --format '{{json .Config.Labels}}' $containerName) | ConvertFrom-Json)
	if ($inspectedId -ne $containerId `
			-or [string]$containerLabels.'com.splitsmart.audit.phase' -ne '6' `
			-or [string]$containerLabels.'com.splitsmart.audit.run' -ne $runId) {
		throw 'Could not prove ownership of the disposable MySQL container.'
	}
	$containerMounts = ((& $dockerPath inspect --format '{{json .Mounts}}' $containerName) | ConvertFrom-Json)
	$databaseMounts = @(
		$containerMounts | Where-Object {
			$_.Type -eq 'volume' -and $_.Destination -eq '/var/lib/mysql'
		}
	)
	if ($databaseMounts.Count -ne 1 -or $databaseMounts[0].Name -ne $volumeName) {
		throw 'The disposable MySQL container is not using its verified audit volume.'
	}
	$portMapping = ((& $dockerPath port $containerName 3306/tcp) | Out-String).Trim()
	if ($portMapping -notmatch '^127\.0\.0\.1:(\d+)$') {
		throw 'The disposable MySQL port is not bound exclusively to loopback.'
	}
	$databasePort = [int]$Matches[1]
	if ($databasePort -eq 3306 -or @(Get-ListenerDetails -Port $databasePort).Count -ne 1) {
		throw 'The disposable MySQL port mapping conflicts with the protected MySQL listener.'
	}
	$databaseReady = $false
	for ($attempt = 0; $attempt -lt 60; $attempt++) {
		$ping = Invoke-DockerCommand -Environment @{ MYSQL_PWD = $mysqlRootPassword } -Arguments @(
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

	Start-AuditApi -Mode console
	if (-not (Test-Path -LiteralPath $summaryPath)) {
		$consoleHealth = Invoke-AuditRequest -Method GET -Path '/api/health' -Label 'console-health' -ExpectedStatus @(200)
		if ([string]$consoleHealth.Body.status -ne 'UP') {
			throw 'The isolated API health response did not report UP.'
		}
		$unauthorized = Invoke-AuditRequest -Method GET -Path '/api/groups' -Label 'console-unauthenticated' -ExpectedStatus @(401)
		$accounts = @{
			owner = New-TestAccount -Role 'owner'
			debtor = New-TestAccount -Role 'debtor'
			creditor = New-TestAccount -Role 'creditor'
			outsider = New-TestAccount -Role 'outsider'
		}
		$primaryGroupId = New-TestGroup -Owner $accounts.owner -Debtor $accounts.debtor `
			-Creditor $accounts.creditor -Label 'settlement-fixture'
		$primaryExpense = Invoke-AuditRequest -Method POST -Path "/api/groups/$primaryGroupId/expenses" `
			-Token $accounts.owner.Token `
			-Body (New-EqualExpenseBody -Description 'Synthetic tracing baseline' -Amount '100.00' `
				-Owner $accounts.owner -Debtor $accounts.debtor -Creditor $accounts.creditor) `
			-Label 'console-seed-expense' -ExpectedStatus @(201)
		if ((ConvertTo-AuditDecimal $primaryExpense.Body.amount) -ne [decimal]100.00) {
			throw 'The primary synthetic expense amount did not persist correctly.'
		}
		[void](Invoke-AuditRequest -Method GET -Path '/api/groups' -Token $accounts.owner.Token `
			-Label 'console-list-groups' -ExpectedStatus @(200))
		[void](Invoke-AuditRequest -Method GET -Path "/api/groups/$primaryGroupId/balances" `
			-Token $accounts.owner.Token -Label 'console-balance' -ExpectedStatus @(200))
	}
	$consoleApiOutput = Stop-AuditApi
	$consoleRaw = $consoleApiOutput.Stdout + "`n" + $consoleApiOutput.Stderr
	$consoleSecurity = Get-RawSecurityInspection -RawText $consoleRaw
	$consoleEvidence = Get-ConsoleTraceEvidence -RawText $consoleRaw
	$consoleRaw = $null
	$consoleApiOutput = $null

	Start-AuditCollector
	Start-AuditApi -Mode otlp
	$databaseMigrations = Invoke-DbQuery "SELECT CONCAT(version, ':', success) FROM flyway_schema_history WHERE version IN ('1','2','3') ORDER BY version"
	$migrationRows = @($databaseMigrations -split "`r?`n" | Where-Object { $_ -match '^[123]:1$' })
	if ($migrationRows.Count -ne 3) {
		throw 'The disposable database did not successfully apply Flyway V1, V2, and V3.'
	}

	$groupTraceId = New-TestGroup -Owner $accounts.owner -Debtor $accounts.debtor `
		-Creditor $accounts.creditor -Label 'group-operations' -AddOutsider
	$groupsResponse = Invoke-AuditRequest -Method GET -Path '/api/groups' -Token $accounts.owner.Token `
		-Label 'group-list' -ExpectedStatus @(200)
	if (-not @($groupsResponse.Body | Where-Object { [long]$_.id -eq $groupTraceId }).Count) {
		throw 'The created group was not present in the authenticated group list.'
	}
	$membersResponse = Invoke-AuditRequest -Method GET -Path "/api/groups/$groupTraceId/members" `
		-Token $accounts.owner.Token -Label 'group-members' -ExpectedStatus @(200)
	if (@($membersResponse.Body).Count -ne 4) {
		throw 'The group member listing did not include all four synthetic members.'
	}

	$expenseGroupId = New-TestGroup -Owner $accounts.owner -Debtor $accounts.debtor `
		-Creditor $accounts.creditor -Label 'expense-operations'
	$equalExpense = Invoke-AuditRequest -Method POST -Path "/api/groups/$expenseGroupId/expenses" `
		-Token $accounts.owner.Token `
		-Body (New-EqualExpenseBody -Description 'Equal split trace' -Amount '10.01' `
			-Owner $accounts.owner -Debtor $accounts.debtor -Creditor $accounts.creditor) `
		-Label 'expense-equal-create' -ExpectedStatus @(201)
	$equalSplitTotal = [decimal]0
	foreach ($split in $equalExpense.Body.splits) {
		$equalSplitTotal += ConvertTo-AuditDecimal $split.amount
	}
	if ($equalSplitTotal -ne [decimal]10.01 -or [string]$equalExpense.Body.currencyCode -ne 'INR') {
		throw 'The EQUAL expense response did not reconcile to INR 10.01.'
	}
	$exactExpense = Invoke-AuditRequest -Method POST -Path "/api/groups/$expenseGroupId/expenses" `
		-Token $accounts.owner.Token `
		-Body (New-ExactExpenseBody -Description 'Exact split trace' -Amount '15.00' `
			-Amounts @('5.00', '6.00', '4.00') -Owner $accounts.owner `
			-Debtor $accounts.debtor -Creditor $accounts.creditor) `
		-Label 'expense-exact-create' -ExpectedStatus @(201)
	$exactId = [long]$exactExpense.Body.id
	$expenseList = Invoke-AuditRequest -Method GET -Path "/api/groups/$expenseGroupId/expenses" `
		-Token $accounts.owner.Token -Label 'expense-list-operations' -ExpectedStatus @(200)
	if (@($expenseList.Body).Count -ne 2) {
		throw 'The expense list did not return both created expenses.'
	}
	$expenseDetails = Invoke-AuditRequest -Method GET -Path "/api/groups/$expenseGroupId/expenses/$exactId" `
		-Token $accounts.owner.Token -Label 'expense-details' -ExpectedStatus @(200)
	if ((ConvertTo-AuditDecimal $expenseDetails.Body.amount) -ne [decimal]15.00) {
		throw 'Expense detail retrieval returned an unexpected amount.'
	}
	$updatedExpense = Invoke-AuditRequest -Method PUT -Path "/api/groups/$expenseGroupId/expenses/$exactId" `
		-Token $accounts.owner.Token `
		-Body (New-ExactExpenseBody -Description 'Updated exact trace' -Amount '18.00' `
			-Amounts @('6.00', '7.00', '5.00') -Owner $accounts.owner `
			-Debtor $accounts.debtor -Creditor $accounts.creditor) `
		-Label 'expense-update' -ExpectedStatus @(200)
	if ((ConvertTo-AuditDecimal $updatedExpense.Body.amount) -ne [decimal]18.00) {
		throw 'The exact expense update returned an unexpected amount.'
	}
	[void](Invoke-AuditRequest -Method DELETE -Path "/api/groups/$expenseGroupId/expenses/$exactId" `
		-Token $accounts.owner.Token -Label 'expense-delete' -ExpectedStatus @(204))
	$remainingExpenses = Invoke-AuditRequest -Method GET -Path "/api/groups/$expenseGroupId/expenses" `
		-Token $accounts.owner.Token -Label 'expense-list-after-delete' -ExpectedStatus @(200)
	if (@($remainingExpenses.Body).Count -ne 1) {
		throw 'The deleted expense remained visible in the group expense list.'
	}

	$expenseScale = [System.Collections.Generic.List[object]]::new()
	foreach ($size in @(5, 25)) {
		$scaleGroupId = New-TestGroup -Owner $accounts.owner -Debtor $accounts.debtor `
			-Creditor $accounts.creditor -Label "expense-scale-$size"
		for ($index = 1; $index -le $size; $index++) {
			$createdExpense = Invoke-AuditRequest -Method POST -Path "/api/groups/$scaleGroupId/expenses" `
				-Token $accounts.owner.Token `
				-Body (New-EqualExpenseBody -Description "Scale expense $index" -Amount '30.00' `
					-Owner $accounts.owner -Debtor $accounts.debtor -Creditor $accounts.creditor) `
				-Label "expense-scale-$size-create" -ExpectedStatus @(201)
			if ((ConvertTo-AuditDecimal $createdExpense.Body.amount) -ne [decimal]30.00) {
				throw "Expense $index in the $size-expense group did not persist correctly."
			}
		}
		$scaleList = Invoke-AuditRequest -Method GET -Path "/api/groups/$scaleGroupId/expenses" `
			-Token $accounts.owner.Token -Label "expenses-list-$size" -ExpectedStatus @(200)
		$scaleBalance = Invoke-AuditRequest -Method GET -Path "/api/groups/$scaleGroupId/balances" `
			-Token $accounts.owner.Token -Label "balances-$size" -ExpectedStatus @(200)
		$scaleSuggestions = Invoke-AuditRequest -Method GET `
			-Path "/api/groups/$scaleGroupId/settlement-suggestions" -Token $accounts.owner.Token `
			-Label "suggestions-$size" -ExpectedStatus @(200)
		$expectedExpenseTotal = [decimal]30.00 * $size
		$expectedSuggestionTotal = [decimal]20.00 * $size
		$netTotal = [decimal]0
		foreach ($balance in $scaleBalance.Body.balances) {
			$netTotal += ConvertTo-AuditDecimal $balance.netBalance
		}
		if (@($scaleList.Body).Count -ne $size `
				-or (ConvertTo-AuditDecimal $scaleBalance.Body.totalExpenseAmount) -ne $expectedExpenseTotal `
				-or $netTotal -ne [decimal]0 `
				-or (ConvertTo-AuditDecimal $scaleSuggestions.Body.totalSuggestedAmount) -ne $expectedSuggestionTotal) {
			throw "Financial API responses did not reconcile for the $size-expense group."
		}
		$groupSettlementCount = [int](Invoke-DbQuery "SELECT COUNT(*) FROM settlements WHERE group_id = $scaleGroupId")
		$expenseScale.Add([PSCustomObject]@{
			ExpenseCount = $size
			MemberCount = 3
			SettlementCount = $groupSettlementCount
			ExpenseListCount = @($scaleList.Body).Count
			BalanceTotal = [string]$scaleBalance.Body.totalExpenseAmount
			SuggestedTotal = [string]$scaleSuggestions.Body.totalSuggestedAmount
			FinancialResponsesReconciled = $true
		})
	}

	$primaryBalances = Invoke-AuditRequest -Method GET -Path "/api/groups/$primaryGroupId/balances" `
		-Token $accounts.owner.Token -Label 'settlement-balances-before' -ExpectedStatus @(200)
	$primarySuggestions = Invoke-AuditRequest -Method GET `
		-Path "/api/groups/$primaryGroupId/settlement-suggestions" -Token $accounts.owner.Token `
		-Label 'settlement-suggestions-before' -ExpectedStatus @(200)
	if ((ConvertTo-AuditDecimal $primarySuggestions.Body.totalSuggestedAmount) -ne [decimal]66.66) {
		throw 'Settlement suggestions did not match the outstanding balances before repayment.'
	}
	$firstRequestId = [Guid]::NewGuid().ToString()
	$firstSettlementBody = @{
		fromUserId = $accounts.debtor.Id
		toUserId = $accounts.owner.Id
		amount = '10.00'
		requestId = $firstRequestId
		note = 'Synthetic tracing settlement'
	}
	$validSettlement = Invoke-AuditRequest -Method POST -Path "/api/groups/$primaryGroupId/settlements" `
		-Token $accounts.debtor.Token -Body $firstSettlementBody `
		-Label 'settlement-valid' -ExpectedStatus @(201)
	if ((ConvertTo-AuditDecimal $validSettlement.Body.amount) -ne [decimal]10.00) {
		throw 'The valid settlement response returned an unexpected amount.'
	}
	[void](Invoke-AuditRequest -Method POST -Path "/api/groups/$primaryGroupId/settlements" `
		-Token $accounts.debtor.Token -Body $firstSettlementBody `
		-Label 'settlement-duplicate' -ExpectedStatus @(409))
	[void](Invoke-AuditRequest -Method POST -Path "/api/groups/$primaryGroupId/settlements" `
		-Token $accounts.creditor.Token -Body @{
			fromUserId = $accounts.creditor.Id
			toUserId = $accounts.owner.Id
			amount = '999.00'
			requestId = [Guid]::NewGuid().ToString()
			note = 'Synthetic excessive repayment'
		} -Label 'settlement-excessive' -ExpectedStatus @(400))
	[void](Invoke-AuditRequest -Method POST -Path "/api/groups/$primaryGroupId/settlements" `
		-Token $accounts.debtor.Token -Body @{
			fromUserId = $accounts.creditor.Id
			toUserId = $accounts.owner.Id
			amount = '1.00'
			requestId = [Guid]::NewGuid().ToString()
			note = 'Synthetic unauthorized sender'
		} -Label 'settlement-unauthorized-sender' -ExpectedStatus @(403))
	[void](Invoke-AuditRequest -Method POST -Path "/api/groups/$primaryGroupId/settlements" `
		-Token $accounts.debtor.Token -Body @{
			fromUserId = $accounts.debtor.Id
			toUserId = $accounts.outsider.Id
			amount = '1.00'
			requestId = [Guid]::NewGuid().ToString()
			note = 'Synthetic invalid receiver'
		} -Label 'settlement-invalid-receiver' -ExpectedStatus @(400))
	$settlementHistory = Invoke-AuditRequest -Method GET -Path "/api/groups/$primaryGroupId/settlements" `
		-Token $accounts.owner.Token -Label 'settlement-history-before-lock' -ExpectedStatus @(200)
	if (@($settlementHistory.Body).Count -ne 1) {
		throw 'Invalid or duplicate repayment requests persisted an unexpected settlement.'
	}

	$lockHolder = Start-DatabaseLockHolder -GroupId $primaryGroupId
	$contendedRequestId = [Guid]::NewGuid().ToString()
	$contendedSettlement = Invoke-AuditRequest -Method POST -Path "/api/groups/$primaryGroupId/settlements" `
		-Token $accounts.debtor.Token -Body @{
			fromUserId = $accounts.debtor.Id
			toUserId = $accounts.owner.Id
			amount = '1.00'
			requestId = $contendedRequestId
			note = 'Synthetic controlled lock comparison'
		} -Label 'settlement-lock-contended' -ExpectedStatus @(201)
	Stop-DatabaseLockHolder
	if ((ConvertTo-AuditDecimal $contendedSettlement.Body.amount) -ne [decimal]1.00) {
		throw 'The controlled lock-contention settlement returned an unexpected amount.'
	}
	$settlementHistoryAfterLock = Invoke-AuditRequest -Method GET -Path "/api/groups/$primaryGroupId/settlements" `
		-Token $accounts.owner.Token -Label 'settlement-history-after-lock' -ExpectedStatus @(200)
	$finalPrimaryBalances = Invoke-AuditRequest -Method GET -Path "/api/groups/$primaryGroupId/balances" `
		-Token $accounts.owner.Token -Label 'settlement-balances-after' -ExpectedStatus @(200)
	if (@($settlementHistoryAfterLock.Body).Count -ne 2) {
		throw 'The valid settlements were not persisted exactly once each.'
	}
	$primaryNetTotal = [decimal]0
	foreach ($balance in $finalPrimaryBalances.Body.balances) {
		$primaryNetTotal += ConvertTo-AuditDecimal $balance.netBalance
	}
	if ($primaryNetTotal -ne [decimal]0) {
		throw 'The primary group ledger did not net to zero after valid repayments.'
	}

	$traceApiOutput = Stop-AuditApi
	$collectorOutput = Stop-AuditCollector
	$detailedRaw = $collectorOutput.Stdout + "`n" + $collectorOutput.Stderr
	$detailedSecurity = Get-RawSecurityInspection -RawText $detailedRaw
	$detailedSpans = @(Get-DetailedSpans -RawText $detailedRaw)
	$httpRoots = @($detailedSpans | Where-Object { $_.Kind -match '^(?i:SERVER)$' })
	$jdbcSpans = @($detailedSpans | Where-Object { $_.IsDatabase })
	if ($httpRoots.Count -eq 0 -or $jdbcSpans.Count -eq 0) {
		throw "Detailed local export did not include HTTP and JDBC spans (HTTP=$($httpRoots.Count), JDBC=$($jdbcSpans.Count))."
	}
	$observations = @(Get-RequestTraceObservations -Spans $detailedSpans)
	$requiredLabels = @(
		'group-list', 'group-members', 'expense-equal-create', 'expense-exact-create',
		'expense-list-operations', 'expense-details', 'expense-update', 'expense-delete',
		'expenses-list-5', 'expenses-list-25', 'balances-5', 'balances-25',
		'suggestions-5', 'suggestions-25', 'settlement-balances-before',
		'settlement-suggestions-before', 'settlement-valid', 'settlement-duplicate',
		'settlement-excessive', 'settlement-unauthorized-sender',
		'settlement-invalid-receiver', 'settlement-history-before-lock',
		'settlement-lock-contended', 'settlement-history-after-lock',
		'settlement-balances-after'
	)
	$observationLabels = @($observations | ForEach-Object { $_.Label })
	foreach ($requiredLabel in $requiredLabels) {
		if ($observationLabels -notcontains $requiredLabel) {
			throw "No detailed server span could be mapped to required request '$requiredLabel'."
		}
	}
	foreach ($observation in $observations) {
		if ($observation.DatabaseSpanCount -gt 0 -and -not $observation.ParentChildVerified) {
			throw "JDBC span parentage could not be verified for request '$($observation.Label)'."
		}
	}
	$settlementObservation = $observations | Where-Object { $_.Label -eq 'settlement-valid' } | Select-Object -First 1
	$lockObservation = $observations | Where-Object { $_.Label -eq 'settlement-lock-contended' } | Select-Object -First 1
	if (-not $settlementObservation.HasForUpdateSpan -or -not $lockObservation.HasForUpdateSpan) {
		throw 'The expected group-row FOR UPDATE JDBC spans were not found in settlement traces.'
	}
	$scaleResults = [System.Collections.Generic.List[object]]::new()
	foreach ($size in @(5, 25)) {
		$listObservation = $observations | Where-Object { $_.Label -eq "expenses-list-$size" } | Select-Object -First 1
		$balanceObservation = $observations | Where-Object { $_.Label -eq "balances-$size" } | Select-Object -First 1
		$suggestionObservation = $observations | Where-Object { $_.Label -eq "suggestions-$size" } | Select-Object -First 1
		$scaleResults.Add([PSCustomObject]@{
			ExpenseCount = $size
			ExpenseListDatabaseSpans = $listObservation.DatabaseSpanCount
			ExpenseListDatabaseMs = $listObservation.DatabaseDurationMs
			ExpenseListOperations = $listObservation.DatabaseOperations
			BalanceDatabaseSpans = $balanceObservation.DatabaseSpanCount
			BalanceDatabaseMs = $balanceObservation.DatabaseDurationMs
			BalanceOperations = $balanceObservation.DatabaseOperations
			SuggestionDatabaseSpans = $suggestionObservation.DatabaseSpanCount
			SuggestionDatabaseMs = $suggestionObservation.DatabaseDurationMs
			SuggestionOperations = $suggestionObservation.DatabaseOperations
		})
	}
	$databaseIntegrity = Get-DatabaseIntegrity
	if ($databaseIntegrity.BrokenReferences -ne 0 -or
		$databaseIntegrity.UnbalancedExpenses -ne 0 -or
		$databaseIntegrity.NonMemberSplits -ne 0 -or
		$databaseIntegrity.NonMemberSettlements -ne 0 -or
		$databaseIntegrity.UnbalancedGroupLedgers -ne 0 -or
		$databaseIntegrity.DuplicateSettlementRequestIds -ne 0) {
		throw 'Disposable MySQL financial-integrity assertions did not all pass.'
	}
	$totalSettlementCount = [int](Invoke-DbQuery 'SELECT COUNT(*) FROM settlements')

	$performance = [System.Collections.Generic.List[object]]::new()
	foreach ($mode in @('baseline', 'none', 'console')) {
		$workload = Measure-ReadWorkload -Mode $mode -GroupId $primaryGroupId `
			-Token $accounts.owner.Token -Warmup 10 -RequestCount 60
		$performance.Add($workload.Performance)
		$performanceOutput = $workload.ProcessOutput.Stdout + "`n" + $workload.ProcessOutput.Stderr
		$performanceSecurity = Get-RawSecurityInspection -RawText $performanceOutput
		if ($performanceSecurity.KnownSecretMatches -gt 0 -or
			$performanceSecurity.BearerTokenMatches -gt 0 -or
			$performanceSecurity.AuthorizationHeaderCaptures -gt 0) {
			throw "Sensitive values were present in raw $mode telemetry or application output."
		}
		$performanceOutput = $null
	}
	$baselinePerf = $performance | Where-Object { $_.Mode -eq 'baseline' } | Select-Object -First 1
	$agentPerf = $performance | Where-Object { $_.Mode -eq 'none' } | Select-Object -First 1
	$consolePerf = $performance | Where-Object { $_.Mode -eq 'console' } | Select-Object -First 1
	$overhead = [PSCustomObject]@{
		AgentNoExporterP50Percent = if ($baselinePerf.P50Ms -gt 0) {
			[math]::Round((($agentPerf.P50Ms - $baselinePerf.P50Ms) / $baselinePerf.P50Ms) * 100, 2)
		} else { $null }
		AgentNoExporterP95Percent = if ($baselinePerf.P95Ms -gt 0) {
			[math]::Round((($agentPerf.P95Ms - $baselinePerf.P95Ms) / $baselinePerf.P95Ms) * 100, 2)
		} else { $null }
		ConsoleVersusAgentNoExporterP50Percent = if ($agentPerf.P50Ms -gt 0) {
			[math]::Round((($consolePerf.P50Ms - $agentPerf.P50Ms) / $agentPerf.P50Ms) * 100, 2)
		} else { $null }
		ConsoleVersusAgentNoExporterP95Percent = if ($agentPerf.P95Ms -gt 0) {
			[math]::Round((($consolePerf.P95Ms - $agentPerf.P95Ms) / $agentPerf.P95Ms) * 100, 2)
		} else { $null }
	}
	$migrationResults = @(
		foreach ($migration in Get-ChildItem -LiteralPath $migrationDirectory -Filter 'V*.sql') {
			$currentHash = (Get-FileHash -LiteralPath $migration.FullName -Algorithm SHA256).Hash
			[PSCustomObject]@{
				Name = $migration.Name
				Unchanged = ($initialMigrationHashes[$migration.Name] -eq $currentHash)
			}
		}
	)
	if (@($migrationResults | Where-Object { -not $_.Unchanged }).Count -gt 0) {
		throw 'One or more Flyway migration files changed during the audit.'
	}

	$selectedObservations = @(
		$observations | Where-Object {
			$_.Label -in @(
				'group-list', 'group-members', 'expense-equal-create', 'expense-exact-create',
				'expense-list-operations', 'expense-details', 'expense-update', 'expense-delete',
				'expenses-list-5', 'expenses-list-25', 'balances-5', 'balances-25',
				'suggestions-5', 'suggestions-25', 'settlement-valid',
				'settlement-duplicate', 'settlement-excessive', 'settlement-unauthorized-sender',
				'settlement-invalid-receiver', 'settlement-lock-contended',
				'settlement-history-after-lock'
			)
		}
	)
	$allJdbcLinked = @($observations | Where-Object { $_.DatabaseSpanCount -gt 0 -and -not $_.ParentChildVerified }).Count -eq 0
	$summary = [ordered]@{
		phase = 6
		runId = $runId
		status = 'PASS'
		javaRuntime = $JavaRuntimeVersion
		openTelemetryJavaAgent = @{
			version = $agentVersion
			sha256 = 'e43a270df53c6f423e0ab157be9c27ccc01d5dc3232e162fe54777573db2f149'
			download = 'official OpenTelemetry Java instrumentation v2.33.1 release'
		}
		localCollector = @{
			version = $collectorVersion
			sha256 = 'ff9a3d79687cca7863bfbea1198542ca0c1800841289541913211d75ab6fea38'
			receiver = 'OTLP gRPC over 127.0.0.1 only'
			exporter = 'debug, detailed'
		}
		isolation = @{
			database = $databaseName
			databaseImage = $image
			databasePort = $databasePort
			apiAddress = "127.0.0.1:$apiPort"
			containerId = $containerId
			collectorPort = $collectorPort
			productionDatabaseUsed = $false
			existingApiTargeted = $false
		}
		consoleSmoke = $consoleEvidence
		detailedTraceCapture = @{
			HttpServerSpanCount = $httpRoots.Count
			JdbcSpanCount = $jdbcSpans.Count
			JdbcSpansLinkedToHttpParent = $allJdbcLinked
			ParentChildVerified = $allJdbcLinked
			RepresentativeTraces = $selectedObservations
		}
		expenseScaling = $scaleResults
		databaseIntegrity = $databaseIntegrity
		totalPersistedSettlements = $totalSettlementCount
		settlementLockComparison = @{
			UncontendedClientMs = $settlementObservation.ClientDurationMs
			UncontendedServerMs = $settlementObservation.ServerDurationMs
			UncontendedForUpdateJdbcMs = ($settlementObservation.SampleDatabaseChildren |
				Where-Object { $_.Operation -match 'SELECT expense_groups' } |
				Measure-Object -Property DurationMs -Sum).Sum
			ContendedClientMs = $lockObservation.ClientDurationMs
			ContendedServerMs = $lockObservation.ServerDurationMs
			ContendedForUpdateJdbcMs = ($lockObservation.SampleDatabaseChildren |
				Where-Object { $_.Operation -match 'SELECT expense_groups' } |
				Measure-Object -Property DurationMs -Sum).Sum
			ExplicitTwoSecondRowLockHeld = $true
			ConnectionAcquisitionWaitMeasured = $false
		}
		performance = @{
			conditions = @($performance)
			overheadPercentages = $overhead
			virtualUsers = 1
			notes = '60 sequential measured balance reads after 10 warm-ups per condition; console export cost is separated from agent-without-exporter cost.'
		}
		security = @{
			console = $consoleSecurity
			detailedCollector = $detailedSecurity
			rawTelemetryPersisted = $false
			requestResponseBodyCaptureEnabled = $false
			sensitiveHeaderCaptureConfigured = $false
			databaseStatementSanitizerEnabled = $true
		}
		flywayMigrations = $migrationResults
		freeGiBBefore = $initialFreeGiB
		freeGiBAfterWork = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
		createdAtUtc = [DateTimeOffset]::UtcNow.ToString('o')
	}
	$summary | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
	$detailedRaw = $null
	$collectorOutput = $null
	$traceApiOutput = $null
	$detailedSpans = $null
	$apiOutput = $null
	Write-Host "Phase 6 evidence: $summaryPath"
	Write-Host "Console spans: HTTP $($consoleEvidence.HttpServerSpanCount), JDBC $($consoleEvidence.JdbcSpanCount); detailed spans: HTTP $($httpRoots.Count), JDBC $($jdbcSpans.Count)."
	Write-Host "Expense scaling DB span counts (5/25): list $($scaleResults[0].ExpenseListDatabaseSpans)/$($scaleResults[1].ExpenseListDatabaseSpans), balances $($scaleResults[0].BalanceDatabaseSpans)/$($scaleResults[1].BalanceDatabaseSpans), suggestions $($scaleResults[0].SuggestionDatabaseSpans)/$($scaleResults[1].SuggestionDatabaseSpans)."
	Write-Host "Financial integrity: PASS. No raw telemetry was written to disk."
} catch {
	$runFailure = $_
	$safeFailureMessage = [string]$_.Exception.Message
	foreach ($secret in @($mysqlPassword, $mysqlRootPassword, $jwtSecret, $testPassword, $databaseUser, $databaseName)) {
		if (-not [string]::IsNullOrEmpty($secret)) {
			$safeFailureMessage = $safeFailureMessage.Replace($secret, '[REDACTED]')
		}
	}
	if ($null -ne $accounts) {
		foreach ($account in $accounts.Values) {
			foreach ($sensitiveValue in @($account.Token, $account.Email)) {
				if (-not [string]::IsNullOrWhiteSpace([string]$sensitiveValue)) {
					$safeFailureMessage = $safeFailureMessage.Replace([string]$sensitiveValue, '[REDACTED]')
				}
			}
		}
	}
	$safeFailureMessage = [regex]::Replace($safeFailureMessage, '(?i)\botel-[a-z0-9-]+@example\.invalid\b', '[REDACTED-EMAIL]')
	$safeFailureMessage = [regex]::Replace($safeFailureMessage, '(?im)^\s*\[otel\.javaagent\b.*$', '[OpenTelemetry runtime log omitted]')
	$safeFailureMessage = [regex]::Replace(
		$safeFailureMessage,
		'(?ms)^Span #\d+\r?\n.*?(?=^Span #\d+|^ScopeSpans #\d+|^ResourceSpans #\d+|\z)',
		'[OpenTelemetry span omitted]')
	if ($safeFailureMessage.Length -gt 2000) {
		$safeFailureMessage = $safeFailureMessage.Substring(0, 2000) + ' [TRUNCATED]'
	}
	$failureLine = $_.InvocationInfo.ScriptLineNumber
} finally {
	try {
		Stop-DatabaseLockHolder
	} catch {
		$cleanupFailure = 'The owned disposable database lock-holder did not stop cleanly.'
	}
	try {
		$remainingApiOutput = Stop-AuditApi
		$remainingApiOutput = $null
	} catch {
		$cleanupFailure = 'The isolated API process did not stop cleanly.'
		if ($null -ne $apiProcess -and -not $apiProcess.HasExited) {
			Stop-Process -Id $apiProcess.Id -Force -ErrorAction SilentlyContinue
		}
	}
	try {
		$remainingCollectorOutput = Stop-AuditCollector
		$remainingCollectorOutput = $null
	} catch {
		$cleanupFailure = 'The local OpenTelemetry Collector did not stop cleanly.'
		if ($null -ne $collectorProcess -and -not $collectorProcess.HasExited) {
			Stop-Process -Id $collectorProcess.Id -Force -ErrorAction SilentlyContinue
		}
	}
	if (Test-Path -LiteralPath $collectorConfigPath) {
		try {
			Remove-Item -LiteralPath $collectorConfigPath -ErrorAction Stop
			$collectorConfigRemoved = -not (Test-Path -LiteralPath $collectorConfigPath)
			if (-not $collectorConfigRemoved -and $null -eq $cleanupFailure) {
				$cleanupFailure = 'The temporary OpenTelemetry Collector configuration remains on disk.'
			}
		} catch {
			$collectorConfigRemoved = $false
			if ($null -eq $cleanupFailure) {
				$cleanupFailure = 'Could not remove the temporary OpenTelemetry Collector configuration.'
			}
		}
	}
	if (-not [string]::IsNullOrWhiteSpace($containerId)) {
		$currentId = ((& $dockerPath inspect --format '{{.Id}}' $containerName 2>$null) | Out-String).Trim()
		if ($LASTEXITCODE -eq 0 -and $currentId -eq $containerId) {
			$currentLabels = ((& $dockerPath inspect --format '{{json .Config.Labels}}' $containerName) | ConvertFrom-Json)
			if ([string]$currentLabels.'com.splitsmart.audit.phase' -eq '6' `
					-and [string]$currentLabels.'com.splitsmart.audit.run' -eq $runId) {
				& $dockerPath stop $containerId 2>$null | Out-Null
				if ($LASTEXITCODE -eq 0) {
					& $dockerPath rm $containerId 2>$null | Out-Null
					if ($LASTEXITCODE -ne 0) {
						$cleanupFailure = 'Could not remove the verified disposable MySQL container.'
					}
				} else {
					$cleanupFailure = 'Could not stop the verified disposable MySQL container.'
				}
			} else {
				$cleanupFailure = 'Refused to remove a container whose audit ownership labels changed.'
			}
		} elseif ($LASTEXITCODE -eq 0) {
			$cleanupFailure = 'Refused to remove a container whose identity changed.'
		}
	}
	$ownedVolumeRemoved = -not $volumeCreated
	if ($volumeCreated) {
		$currentVolumeName = ((& $dockerPath volume inspect --format '{{.Name}}' $volumeName 2>$null) | Out-String).Trim()
		if ($LASTEXITCODE -eq 0 -and $currentVolumeName -eq $volumeName) {
			$currentVolumeLabels = ((& $dockerPath volume inspect --format '{{json .Labels}}' $volumeName) | ConvertFrom-Json)
			if ([string]$currentVolumeLabels.'com.splitsmart.audit.phase' -eq '6' -and
				[string]$currentVolumeLabels.'com.splitsmart.audit.run' -eq $runId) {
				$volumeUsers = @(& $dockerPath ps --all --quiet --no-trunc --filter "volume=$volumeName")
				if ($volumeUsers.Count -eq 0) {
					& $dockerPath volume rm $volumeName 2>$null | Out-Null
					if ($LASTEXITCODE -eq 0) {
						$ownedVolumeRemoved = $true
					} elseif ($null -eq $cleanupFailure) {
						$cleanupFailure = 'Could not remove the verified disposable MySQL volume.'
					}
				} elseif ($null -eq $cleanupFailure) {
					$cleanupFailure = 'Refused to remove the disposable MySQL volume while a container still uses it.'
				}
			} elseif ($null -eq $cleanupFailure) {
				$cleanupFailure = 'Refused to remove a volume whose audit ownership labels changed.'
			}
		} elseif ($null -eq $cleanupFailure) {
			$cleanupFailure = 'The owned disposable MySQL volume disappeared before cleanup verification.'
		}
	}
	$finalListeners = @{}
	foreach ($port in @(3306, 8081, $apiPort)) {
		$finalListeners[[string]$port] = @(Get-ListenerDetails -Port $port)
		if ($port -in $protectedListenerPorts) {
			$before = @($initialListeners[[string]$port] | ForEach-Object { $_.OwnerId } | Sort-Object -Unique)
			$after = @($finalListeners[[string]$port] | ForEach-Object { $_.OwnerId } | Sort-Object -Unique)
			if (($before -join ',') -ne ($after -join ',')) {
				$protectedListenersPreserved = $false
				if ($null -eq $cleanupFailure) {
					$cleanupFailure = "Protected listener process ownership changed on port $port."
				}
			}
		}
	}
	if (@($finalListeners[[string]$apiPort]).Count -ne 0) {
		$cleanupFailure = "Audit API port $apiPort remains occupied after cleanup."
	}
	if ($null -ne $collectorPort -and @(Get-ListenerDetails -Port $collectorPort).Count -ne 0) {
		$cleanupFailure = 'The temporary local collector receiver remains occupied after cleanup.'
	}
	if ($null -ne $databasePort -and @(Get-ListenerDetails -Port $databasePort).Count -ne 0) {
		$cleanupFailure = 'The disposable database host port remains occupied after cleanup.'
	}
	$finalContainers = @(& $dockerPath ps --all --quiet --no-trunc | Sort-Object)
	if (($initialContainers -join ',') -ne ($finalContainers -join ',')) {
		if ($null -eq $cleanupFailure) {
			$cleanupFailure = 'The complete Docker container inventory differs from the preflight snapshot.'
		}
	}
	$finalVolumes = @(& $dockerPath volume ls --quiet | Sort-Object)
	if (($initialVolumes -join ',') -ne ($finalVolumes -join ',')) {
		if ($null -eq $cleanupFailure) {
			$cleanupFailure = 'The complete Docker volume inventory differs from the preflight snapshot.'
		}
	}
	$finalImageId = ((& $dockerPath image inspect $image --format '{{.Id}}' 2>$null) | Out-String).Trim()
	if ($finalImageId -ne $initialImageId) {
		$cleanupFailure = 'The cached MySQL image changed unexpectedly.'
	}
	foreach ($migration in Get-ChildItem -LiteralPath $migrationDirectory -Filter 'V*.sql' -ErrorAction SilentlyContinue) {
		$currentHash = (Get-FileHash -LiteralPath $migration.FullName -Algorithm SHA256).Hash
		if ($initialMigrationHashes.ContainsKey($migration.Name) -and
			$initialMigrationHashes[$migration.Name] -ne $currentHash) {
			$cleanupFailure = 'A Flyway migration file changed during the audit.'
		}
	}
	$finalFreeGiB = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
	if ($null -ne $summary) {
		$summary.cleanup = [ordered]@{
			status = if ($null -eq $cleanupFailure) { 'PASS' } else { 'FAIL' }
			freeGiBBefore = $initialFreeGiB
			freeGiBAfter = $finalFreeGiB
			protectedListenersPreserved = $protectedListenersPreserved
			auditApiPortFree = (@($finalListeners[[string]$apiPort]).Count -eq 0)
			collectorConfigRemoved = $collectorConfigRemoved
			collectorPortFree = ($null -eq $collectorPort -or @(Get-ListenerDetails -Port $collectorPort).Count -eq 0)
			databasePortFree = ($null -eq $databasePort -or @(Get-ListenerDetails -Port $databasePort).Count -eq 0)
			dockerContainerInventoryUnchanged = (($initialContainers -join ',') -eq ($finalContainers -join ','))
			dockerVolumeInventoryUnchanged = (($initialVolumes -join ',') -eq ($finalVolumes -join ','))
			auditVolumeRemoved = $ownedVolumeRemoved
			auditContainerRemoved = if ($null -eq $containerId) {
				$true
			} else {
				((@(& $dockerPath ps --all --quiet --filter "id=$containerId") -join '').Trim().Length -eq 0)
			}
			cachedImageUnchanged = ($finalImageId -eq $initialImageId)
			flywayMigrationsUnchanged = ($null -eq $cleanupFailure)
			failure = $cleanupFailure
		}
		if ($cleanupFailure) {
			$summary.status = 'FAIL'
		}
		$summary.freeGiBAfterCleanup = $finalFreeGiB
		$summary | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
	} elseif (-not (Test-Path -LiteralPath $summaryPath)) {
		[ordered]@{
			phase = 6
			runId = $runId
			status = 'BLOCKED'
			failureType = if ($null -ne $runFailure) { $runFailure.Exception.GetType().FullName } else { 'UnknownFailure' }
			failureLine = $failureLine
			failure = $safeFailureMessage
			cleanup = [ordered]@{
				status = if ($null -eq $cleanupFailure) { 'PASS' } else { 'FAIL' }
				protectedListenersPreserved = $protectedListenersPreserved
				auditApiPortFree = (@($finalListeners[[string]$apiPort]).Count -eq 0)
				collectorConfigRemoved = $collectorConfigRemoved
				collectorPortFree = ($null -eq $collectorPort -or @(Get-ListenerDetails -Port $collectorPort).Count -eq 0)
				databasePortFree = ($null -eq $databasePort -or @(Get-ListenerDetails -Port $databasePort).Count -eq 0)
				dockerContainerInventoryUnchanged = (($initialContainers -join ',') -eq ($finalContainers -join ','))
				dockerVolumeInventoryUnchanged = (($initialVolumes -join ',') -eq ($finalVolumes -join ','))
				auditVolumeRemoved = $ownedVolumeRemoved
				auditContainerRemoved = if ($null -eq $containerId) {
					$true
				} else {
					((@(& $dockerPath ps --all --quiet --filter "id=$containerId") -join '').Trim().Length -eq 0)
				}
				cachedImageUnchanged = ($finalImageId -eq $initialImageId)
				failure = $cleanupFailure
			}
			freeGiBBefore = $initialFreeGiB
			freeGiBAfterCleanup = $finalFreeGiB
			rawTelemetryPersisted = $false
		} | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
	}
	$accounts = $null
	$mysqlPassword = $null
	$mysqlRootPassword = $null
	$jwtSecret = $null
	$testPassword = $null
	Write-Host "Cleanup: $(if ($null -eq $cleanupFailure) { 'PASS' } else { 'FAIL' }); C: free space $initialFreeGiB GiB before / $finalFreeGiB GiB after."
}

if ($null -ne $runFailure) {
	Write-Host "Phase 6 execution failure (line $failureLine): $safeFailureMessage"
	throw "Phase 6 audit failed. Evidence summary: $summaryPath"
}
if ($null -ne $cleanupFailure) {
	throw "Phase 6 cleanup failed: $cleanupFailure. Evidence summary: $summaryPath"
}
