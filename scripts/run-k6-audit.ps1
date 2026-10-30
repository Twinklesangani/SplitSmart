[CmdletBinding()]
param(
	[ValidateSet('all', 'smoke', 'read', 'mixed', 'contention')]
	[string]$Scenario = 'all',
	[int]$ApiPort = 18081,
	[string]$K6Path
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backendRoot = Join-Path $repositoryRoot 'backend'
$jarPath = Join-Path $backendRoot 'target\splitsmart-backend-0.0.1-SNAPSHOT.jar'
$javaPath = Join-Path $HOME '.jdks\temurin-21\bin\java.exe'
$k6ScriptPath = Join-Path $PSScriptRoot 'performance\k6\splitsmart.js'
$reportRoot = Join-Path $backendRoot 'target\reports\k6'
$image = 'mysql:8.4.13'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$containerName = "splitsmart-k6-$runId"
$databaseName = "splitsmart_perf_$runId"
$databaseUser = "p5_$runId"
$baseUrl = "http://127.0.0.1:$ApiPort"
$runSummaryPath = Join-Path $reportRoot "phase5-$runId-summary.json"
$resourcePath = Join-Path $reportRoot "phase5-$runId-resources.csv"
$containerId = $null
$databasePort = $null
$apiProcess = $null
$apiStdoutTask = $null
$apiStderrTask = $null
$apiStdoutPath = Join-Path $reportRoot "phase5-$runId-backend.stdout.log"
$apiStderrPath = Join-Path $reportRoot "phase5-$runId-backend.stderr.log"
$resourceSamples = [System.Collections.Generic.List[object]]::new()
$scenarioResults = [System.Collections.Generic.List[object]]::new()
$cleanupFailure = $null
$cleanupStatus = 'NOT COMPLETED'

function Get-ListenerDetails {
	param([int]$Port)

	$netstatPath = Join-Path $env:WINDIR 'System32\netstat.exe'
	$output = @(& $netstatPath -ano -p tcp 2>&1)
	if ($LASTEXITCODE -ne 0) {
		throw 'Could not inspect Windows TCP listener ownership with netstat.'
	}
	$pattern = '^\s*TCP\s+(\S+):' + [regex]::Escape([string]$Port) + '\s+\S+\s+LISTENING\s+(\d+)\s*$'
	return @(
		$output |
			ForEach-Object {
				$match = [regex]::Match([string]$_, $pattern)
				if ($match.Success) {
					[PSCustomObject]@{
						LocalAddress = $match.Groups[1].Value
						OwnerId = $match.Groups[2].Value
					}
				}
			}
	)
}

function Get-ListenerOwners {
	param([int]$Port)

	return @(
		Get-ListenerDetails -Port $Port |
			ForEach-Object { $_.OwnerId } |
			Sort-Object -Unique
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

function New-RandomHex {
	param([int]$ByteCount = 32)

	$bytes = New-Object byte[] $ByteCount
	$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
	try {
		$generator.GetBytes($bytes)
	} finally {
		$generator.Dispose()
	}
	return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}

function New-RandomBase64Secret {
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
	param([Parameter(Mandatory)][string]$Sql)

	$result = Invoke-DockerCommand -Environment @{ MYSQL_PWD = $databaseRootPassword } -Arguments @(
		'exec', '--env', 'MYSQL_PWD', $containerName, 'mysql',
		'--batch', '--skip-column-names', '--user=root', $databaseName, "--execute=$Sql"
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

function Get-TableCounts {
	return Invoke-DbQuery @'
SELECT CONCAT(
  (SELECT COUNT(*) FROM users), ',',
  (SELECT COUNT(*) FROM expense_groups), ',',
  (SELECT COUNT(*) FROM group_members), ',',
  (SELECT COUNT(*) FROM expenses), ',',
  (SELECT COUNT(*) FROM expense_splits), ',',
  (SELECT COUNT(*) FROM settlements)
)
'@
}

function Assert-DatabaseIntegrity {
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
		throw "Database integrity failure: $brokenReferences persisted foreign-key references are orphaned."
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
		throw "Financial integrity failure: $unbalancedExpenses expense split totals do not reconcile."
	}

	$nonMemberSplits = Invoke-DbQuery @'
SELECT COUNT(*)
FROM expense_splits s
JOIN expenses e ON e.id = s.expense_id
LEFT JOIN group_members m ON m.group_id = e.group_id AND m.user_id = s.user_id
WHERE m.id IS NULL
'@
	$nonMemberSettlements = Invoke-DbQuery @'
SELECT COUNT(*)
FROM settlements s
LEFT JOIN group_members sender ON sender.group_id = s.group_id AND sender.user_id = s.from_user_id
LEFT JOIN group_members receiver ON receiver.group_id = s.group_id AND receiver.user_id = s.to_user_id
WHERE sender.id IS NULL OR receiver.id IS NULL
'@
	if ([int]$nonMemberSplits -ne 0 -or [int]$nonMemberSettlements -ne 0) {
		throw "Financial integrity failure: $nonMemberSplits splits and $nonMemberSettlements settlements reference nonmembers."
	}

	$unbalancedGroups = Invoke-DbQuery @'
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
'@
	if ([int]$unbalancedGroups -ne 0) {
		throw "Financial integrity failure: $unbalancedGroups group ledgers do not net to zero."
	}

	$duplicateRequestIds = Invoke-DbQuery @'
SELECT COUNT(*)
FROM (
  SELECT group_id, request_id
  FROM settlements
  GROUP BY group_id, request_id
  HAVING COUNT(*) > 1
) duplicates
'@
	if ([int]$duplicateRequestIds -ne 0) {
		throw "Idempotency integrity failure: $duplicateRequestIds duplicate settlement request IDs persisted."
	}

	return [PSCustomObject]@{
		BrokenReferences = [int]$brokenReferences
		UnbalancedExpenses = [int]$unbalancedExpenses
		NonMemberSplits = [int]$nonMemberSplits
		NonMemberSettlements = [int]$nonMemberSettlements
		UnbalancedGroupLedgers = [int]$unbalancedGroups
		DuplicateSettlementRequestIds = [int]$duplicateRequestIds
	}
}

function Get-K6Metric {
	param(
		[Parameter(Mandatory)]$Summary,
		[Parameter(Mandatory)][string]$Name
	)

	$property = $Summary.metrics.PSObject.Properties[$Name]
	if ($null -eq $property) {
		return $null
	}
	return $property.Value
}

function Get-K6MetricValue {
	param(
		[Parameter(Mandatory)]$Summary,
		[Parameter(Mandatory)][string]$Metric,
		[Parameter(Mandatory)][string]$Value
	)

	$metricData = Get-K6Metric -Summary $Summary -Name $Metric
	if ($null -eq $metricData) {
		return $null
	}
	$valueProperty = $metricData.PSObject.Properties[$Value]
	if ($null -eq $valueProperty -and $null -ne $metricData.values) {
		$valueProperty = $metricData.values.PSObject.Properties[$Value]
	}
	if ($null -eq $valueProperty) {
		return $null
	}
	return $valueProperty.Value
}

function Add-ResourceSample {
	param([string]$Name)

	$sampleTime = [DateTime]::UtcNow.ToString('o')
	$apiMemoryMiB = $null
	$apiCpuSeconds = $null
	if ($null -ne $apiProcess -and -not $apiProcess.HasExited) {
		$apiStats = Get-Process -Id $apiProcess.Id -ErrorAction Stop
		$apiMemoryMiB = [math]::Round($apiStats.WorkingSet64 / 1MB, 2)
		$apiCpuSeconds = [math]::Round($apiStats.CPU, 2)
	}

	$dbCpu = $null
	$dbMemory = $null
	$dbSampleError = $null
	$stats = Invoke-DockerCommand -Arguments @(
		'stats', '--no-stream', '--format', '{{.CPUPerc}},{{.MemUsage}}', $containerId
	)
	if ($stats.ExitCode -eq 0 -and $stats.Output.Count -gt 0) {
		$values = ([string]$stats.Output[0]).Split(',')
		if ($values.Count -eq 2) {
			$dbCpu = $values[0].Trim()
			$dbMemory = $values[1].Trim()
		} else {
			$dbSampleError = 'docker stats returned an unexpected format.'
		}
	} else {
		$dbSampleError = (($stats.Output | ForEach-Object { [string]$_ }) -join ' ').Trim()
	}

	$resourceSamples.Add([PSCustomObject]@{
		Scenario = $Name
		TimestampUtc = $sampleTime
		ApiProcessId = if ($null -ne $apiProcess) { $apiProcess.Id } else { $null }
		ApiWorkingSetMiB = $apiMemoryMiB
		ApiCpuSeconds = $apiCpuSeconds
		MySqlContainerCpu = $dbCpu
		MySqlContainerMemory = $dbMemory
		MySqlSampleError = $dbSampleError
	})
}

function ConvertTo-QuotedArgument {
	param([string]$Value)

	return '"' + $Value.Replace('"', '\"') + '"'
}

function Invoke-K6Scenario {
	param([Parameter(Mandatory)][string]$Name)

	$summaryPath = Join-Path $reportRoot "phase5-$runId-$Name-summary.json"
	$rawSummaryPath = Join-Path $env:TEMP "splitsmart-phase5-$runId-$Name-summary.raw.json"
	$stdoutPath = Join-Path $reportRoot "phase5-$runId-$Name.stdout.log"
	$stderrPath = Join-Path $reportRoot "phase5-$runId-$Name.stderr.log"
	$k6Arguments = @(
		'run',
		'--summary-export', $rawSummaryPath,
		'--env', "SCENARIO=$Name",
		'--env', "BASE_URL=$baseUrl",
		'--env', "RUN_ID=$runId",
		$k6ScriptPath
	)
	$processInfo = New-Object System.Diagnostics.ProcessStartInfo
	$processInfo.FileName = $resolvedK6Path
	$processInfo.WorkingDirectory = $repositoryRoot
	$processInfo.Arguments = (($k6Arguments | ForEach-Object { ConvertTo-QuotedArgument ([string]$_) }) -join ' ')
	$processInfo.UseShellExecute = $false
	$processInfo.CreateNoWindow = $true
	$processInfo.RedirectStandardOutput = $true
	$processInfo.RedirectStandardError = $true
	$k6Process = [System.Diagnostics.Process]::Start($processInfo)
	$stdoutTask = $k6Process.StandardOutput.ReadToEndAsync()
	$stderrTask = $k6Process.StandardError.ReadToEndAsync()
	$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
	$lastSample = [DateTime]::UtcNow
	$timedOut = $false
	try {
		while (-not $k6Process.WaitForExit(5000)) {
			if (([DateTime]::UtcNow - $lastSample).TotalSeconds -ge 5) {
				Add-ResourceSample -Name $Name
				$lastSample = [DateTime]::UtcNow
			}
			if ($stopwatch.Elapsed.TotalMinutes -gt 5) {
				$timedOut = $true
				$k6Process.Kill()
				$k6Process.WaitForExit()
				break
			}
		}
		$k6Process.WaitForExit()
		$stopwatch.Stop()
		Add-ResourceSample -Name $Name
		$stdout = $stdoutTask.GetAwaiter().GetResult()
		$stderr = $stderrTask.GetAwaiter().GetResult()
		[System.IO.File]::WriteAllText($stdoutPath, $stdout)
		[System.IO.File]::WriteAllText($stderrPath, $stderr)
		if (Test-Path -LiteralPath $rawSummaryPath) {
			$summary = Get-Content -LiteralPath $rawSummaryPath -Raw | ConvertFrom-Json
			if ($summary.PSObject.Properties['setup_data']) {
				$summary.PSObject.Properties.Remove('setup_data')
			}
			$summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
			Remove-Item -LiteralPath $rawSummaryPath -Force
		}
		if ($timedOut) {
			throw "k6 $Name workload exceeded the five-minute safety timeout."
		}
		if ($k6Process.ExitCode -ne 0) {
			$tail = (($stdout -split "`r?`n" | Select-Object -Last 80) -join "`n").Trim()
			throw "k6 $Name exited with code $($k6Process.ExitCode). Output: $tail"
		}
		if (-not (Test-Path -LiteralPath $summaryPath)) {
			throw "k6 $Name succeeded without writing its summary export."
		}

		$summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
		$endpointNames = @(
			'groups_list', 'expenses_list', 'balances', 'settlements_list',
			'settlement_suggestions', 'expense_create', 'settlement_create'
		)
		$endpoints = @()
		foreach ($endpoint in $endpointNames) {
			$requests = Get-K6MetricValue -Summary $summary -Metric "requests_$endpoint" -Value 'count'
			$trendMetric = Get-K6Metric -Summary $summary -Name "latency_${endpoint}_ms"
			if ($null -ne $requests -and $null -ne $trendMetric) {
				$endpoints += [PSCustomObject]@{
					Name = $endpoint
					Requests = [long]$requests
					AverageMs = $trendMetric.avg
					P95Ms = $trendMetric.'p(95)'
					MaximumMs = $trendMetric.max
				}
			}
		}
		$checksRate = Get-K6MetricValue -Summary $summary -Metric 'checks' -Value 'value'
		$errorRate = Get-K6MetricValue -Summary $summary -Metric 'unexpected_errors' -Value 'value'
		$httpFailureRate = Get-K6MetricValue -Summary $summary -Metric 'http_req_failed' -Value 'value'
		$readEndpointNames = @(
			'groups_list', 'expenses_list', 'balances', 'settlements_list', 'settlement_suggestions'
		)
		$writeEndpointNames = @('expense_create', 'settlement_create')
		$readRequestCount = [long](($endpoints | Where-Object { $_.Name -in $readEndpointNames } |
			Measure-Object -Property Requests -Sum).Sum)
		$writeRequestCount = [long](($endpoints | Where-Object { $_.Name -in $writeEndpointNames } |
			Measure-Object -Property Requests -Sum).Sum)
		$readP95 = $null
		$writeP95 = $null
		if ($readRequestCount -gt 0) {
			$readP95 = Get-K6MetricValue -Summary $summary -Metric 'http_req_duration{operation:read}' -Value 'p(95)'
		}
		if ($writeRequestCount -gt 0) {
			$writeP95 = Get-K6MetricValue -Summary $summary -Metric 'http_req_duration{operation:write}' -Value 'p(95)'
		}
		$thresholds = @(
			[PSCustomObject]@{
				Metric = 'Functional checks'
				Expression = 'rate = 1'
				Passed = $null -ne $checksRate -and [double]$checksRate -eq 1
			},
			[PSCustomObject]@{
				Metric = 'Unexpected error rate'
				Expression = 'rate < 0.01'
				Passed = $null -ne $errorRate -and [double]$errorRate -lt 0.01
			},
			[PSCustomObject]@{
				Metric = 'HTTP request failure rate'
				Expression = 'rate < 0.01'
				Passed = $null -ne $httpFailureRate -and [double]$httpFailureRate -lt 0.01
			}
		)
		if ($null -ne $readP95) {
			$thresholds += [PSCustomObject]@{
				Metric = 'Read p95'
				Expression = 'p(95) < 750 ms'
				Passed = [double]$readP95 -lt 750
			}
		}
		if ($null -ne $writeP95) {
			$thresholds += [PSCustomObject]@{
				Metric = 'Write p95'
				Expression = 'p(95) < 1500 ms'
				Passed = [double]$writeP95 -lt 1500
			}
		}
		if (@($thresholds | Where-Object { -not $_.Passed }).Count -gt 0) {
			throw "The $Name workload's measured metrics did not satisfy the configured acceptance thresholds."
		}
		$result = [PSCustomObject]@{
			Scenario = $Name
			ExitCode = [int]$k6Process.ExitCode
			ElapsedSeconds = [math]::Round($stopwatch.Elapsed.TotalSeconds, 2)
			WorkloadRequests = Get-K6MetricValue -Summary $summary -Metric 'workload_requests' -Value 'count'
			WorkloadRequestsPerSecond = Get-K6MetricValue -Summary $summary -Metric 'workload_requests' -Value 'rate'
			MaximumVUs = Get-K6MetricValue -Summary $summary -Metric 'vus_max' -Value 'max'
			ReadRequests = $readRequestCount
			WriteRequests = $writeRequestCount
			ReadP95Ms = $readP95
			WriteP95Ms = $writeP95
			UnexpectedErrorRate = $errorRate
			HttpRequestsPerSecond = Get-K6MetricValue -Summary $summary -Metric 'http_reqs' -Value 'rate'
			FailedHttpRequestRate = $httpFailureRate
			ChecksPassed = Get-K6MetricValue -Summary $summary -Metric 'checks' -Value 'passes'
			ChecksFailed = Get-K6MetricValue -Summary $summary -Metric 'checks' -Value 'fails'
			ExpectedSettlementConflicts = Get-K6MetricValue -Summary $summary -Metric 'expected_settlement_conflicts' -Value 'count'
			CreatedSettlements = Get-K6MetricValue -Summary $summary -Metric 'created_settlements' -Value 'count'
			Endpoints = $endpoints
			Thresholds = $thresholds
			SummaryFile = $summaryPath
			StdoutFile = $stdoutPath
		}
		$scenarioResults.Add($result)
		Write-Host "$Name`: $($result.WorkloadRequests) workload requests; $($result.UnexpectedErrorRate) unexpected error rate; k6 exit $($result.ExitCode)."
		return $result
	} finally {
		if (Test-Path -LiteralPath $rawSummaryPath) {
			Remove-Item -LiteralPath $rawSummaryPath -Force
		}
		$k6Process.Dispose()
	}
}

if ($ApiPort -le 1024 -or $ApiPort -eq 3306 -or $ApiPort -eq 8081) {
	throw 'Choose a non-privileged audit API port other than protected ports 3306 and 8081.'
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
	throw 'Docker CLI is unavailable; no audit resources were started.'
}
& docker info --format '{{.ServerVersion}}' 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
	throw 'Docker daemon is unavailable; no audit resources were started.'
}
if (-not (Test-Path -LiteralPath $javaPath)) {
	throw "Java 21 was not found at $javaPath."
}
if (-not (Test-Path -LiteralPath $jarPath) -or -not (Test-Path -LiteralPath $k6ScriptPath)) {
	throw 'Build the backend jar and ensure the k6 workload script exists before running the audit.'
}
if ([math]::Round((Get-PSDrive C).Free / 1GB, 2) -lt 5) {
	throw 'Less than 5 GiB is available on C:; refusing to run the performance audit.'
}
if ((Get-ListenerOwners -Port $ApiPort).Count -gt 0) {
	throw "Loopback API port $ApiPort is already in use; no listener will be replaced."
}

$javaRelease = Join-Path $HOME '.jdks\temurin-21\release'
if (-not (Test-Path -LiteralPath $javaRelease) `
		-or (Get-Content $javaRelease | Select-String '^JAVA_VERSION="21\.') -eq $null) {
	throw 'The audit backend must run on the installed Java 21 runtime.'
}
if ([string]::IsNullOrWhiteSpace($K6Path)) {
	$K6Path = Join-Path $env:LOCALAPPDATA 'SplitSmartAudit\k6\v2.3.0\k6-v2.3.0-windows-amd64\k6.exe'
	if (-not (Test-Path -LiteralPath $K6Path)) {
		$availableK6 = Get-Command k6 -ErrorAction SilentlyContinue
		if ($availableK6) {
			$K6Path = $availableK6.Source
		}
	}
}
if (-not (Test-Path -LiteralPath $K6Path)) {
	throw 'Pinned k6 v2.3.0 was not found. Install the official binary or pass -K6Path.'
}
$resolvedK6Path = (Resolve-Path -LiteralPath $K6Path).Path
$k6Version = (& $resolvedK6Path version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $k6Version -notmatch 'k6(?:\.exe)? v2\.3\.0\b') {
	throw "The audit requires exactly k6 v2.3.0; found: $k6Version"
}

$initialListeners = @{
	MySQL = @(Get-ListenerOwners -Port 3306)
	LiveApi = @(Get-ListenerOwners -Port 8081)
}
$initialContainers = @(& docker ps --quiet | Sort-Object)
$initialImageId = (& docker image inspect $image --format '{{.Id}}' 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($initialImageId)) {
	throw "Required cached image $image is not available; the audit will not pull an image."
}
$initialCounts = $null
$initialFreeGiB = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
$databasePassword = New-RandomHex
$databaseRootPassword = New-RandomHex
$jwtSecret = New-RandomBase64Secret
$dockerEnvironment = @{
	MYSQL_DATABASE = $databaseName
	MYSQL_USER = $databaseUser
	MYSQL_PASSWORD = $databasePassword
	MYSQL_ROOT_PASSWORD = $databaseRootPassword
}
$expectedSettlementCount = 0

New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null

try {
	$containerResult = Invoke-DockerCommand -Environment $dockerEnvironment -Arguments @(
		'run', '--detach',
		'--name', $containerName,
		'--label', 'com.splitsmart.audit.phase=5',
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
		throw "Could not create the disposable MySQL container: $message"
	}
	$containerId = (($containerResult.Output | ForEach-Object { [string]$_ }) -join '').Trim()
	$inspectedId = (& docker inspect --format '{{.Id}}' $containerName 2>$null).Trim()
	$containerLabels = (& docker inspect --format '{{json .Config.Labels}}' $containerName | ConvertFrom-Json)
	if ($inspectedId -ne $containerId `
			-or [string]$containerLabels.'com.splitsmart.audit.phase' -ne '5' `
			-or [string]$containerLabels.'com.splitsmart.audit.run' -ne $runId) {
		throw 'Could not verify ownership of the disposable MySQL container.'
	}
	$portMapping = (& docker port $containerName 3306/tcp).Trim()
	if ($portMapping -notmatch '^127\.0\.0\.1:(\d+)$') {
		throw 'The disposable MySQL port is not bound exclusively to loopback.'
	}
	$databasePort = [int]$Matches[1]
	if ($databasePort -eq 3306) {
		throw 'The disposable MySQL container unexpectedly maps to the protected development MySQL port.'
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
	$processInfo.Arguments = "-jar `"$jarPath`" --server.address=127.0.0.1 --server.port=$ApiPort"
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
	$apiStdoutTask = $apiProcess.StandardOutput.ReadToEndAsync()
	$apiStderrTask = $apiProcess.StandardError.ReadToEndAsync()

	$ready = $false
	$lastHealthError = 'No response was received.'
	for ($attempt = 0; $attempt -lt 60; $attempt++) {
		if ($apiProcess.HasExited) {
			throw "Isolated backend exited during startup with code $($apiProcess.ExitCode)."
		}
		try {
			$health = Invoke-WebRequest -Uri "$baseUrl/api/health" -Method Get -TimeoutSec 3 -UseBasicParsing
			if ([int]$health.StatusCode -eq 200) {
				$ready = $true
				break
			}
			$lastHealthError = "HTTP $([int]$health.StatusCode)"
		} catch {
			$lastHealthError = $_.Exception.Message
		}
		Start-Sleep -Seconds 2
	}
	if (-not $ready) {
		throw "The isolated API did not become ready within 120 seconds. Last health result: $lastHealthError"
	}
	$apiListeners = @(Get-ListenerOwners -Port $ApiPort)
	if ($apiListeners.Count -ne 1 -or $apiListeners[0] -ne [string]$apiProcess.Id) {
		throw 'The audit API listener is not owned exclusively by the process started by this run.'
	}
	if (-not (Test-LoopbackListener -Port $ApiPort -ExpectedProcessId ([string]$apiProcess.Id))) {
		throw 'The audit API listener is not bound exclusively to 127.0.0.1.'
	}

	$migrationResult = Invoke-DbQuery "SELECT CONCAT(COUNT(*), ',', SUM(success = 1)) FROM flyway_schema_history"
	if ($migrationResult -ne '3,3') {
		throw "Expected three successful Flyway migrations in the disposable database; found $migrationResult."
	}
	if ((Invoke-DbQuery 'SELECT DATABASE()') -ne $databaseName) {
		throw 'The active database does not match the generated disposable database name.'
	}
	$initialCounts = Get-TableCounts
	if ($initialCounts -ne '0,0,0,0,0,0') {
		throw "The disposable database was not empty after migration: $initialCounts."
	}

	$scenarios = if ($Scenario -eq 'all') {
		@('smoke', 'read', 'mixed', 'contention')
	} else {
		@($Scenario)
	}
	$runNumber = 0
	foreach ($name in $scenarios) {
		$runNumber++
		$beforeCounts = (Get-TableCounts).Split(',')
		$result = Invoke-K6Scenario -Name $name
		$afterCounts = (Get-TableCounts).Split(',')
		if ([int]$afterCounts[1] -ne ([int]$beforeCounts[1] + 1)) {
			throw "The $name run did not create exactly one API-backed group fixture."
		}
		if ([int]$afterCounts[0] -ne ([int]$beforeCounts[0] + 2) `
				-or [int]$afterCounts[2] -ne ([int]$beforeCounts[2] + 2) `
				-or [int]$afterCounts[3] -lt ([int]$beforeCounts[3] + 1) `
				-or [int]$afterCounts[4] -lt ([int]$beforeCounts[4] + 2)) {
			throw "The $name run did not persist its two-user, one-expense API fixture."
		}
		if ($name -eq 'smoke' -or $name -eq 'contention') {
			$expectedSettlementCount++
		}
		if ([int]$afterCounts[5] -ne $expectedSettlementCount) {
			throw "The $name run persisted an unexpected number of settlements."
		}
		if ($name -eq 'contention' `
				-and ($result.CreatedSettlements -ne 1 -or $result.ExpectedSettlementConflicts -ne 7)) {
			throw 'The controlled duplicate-request race did not yield exactly one create and seven expected 409 conflicts.'
		}
		if ($name -eq 'read' -and [int]$afterCounts[3] -ne ([int]$beforeCounts[3] + 1)) {
			throw 'The read-only workload unexpectedly wrote additional expenses.'
		}
		if ($name -eq 'mixed' -and [int]$afterCounts[3] -le ([int]$beforeCounts[3] + 1)) {
			throw 'The mixed workload did not persist any expense writes beyond setup.'
		}
		$integrity = Assert-DatabaseIntegrity
		$result | Add-Member -NotePropertyName DatabaseCounts -NotePropertyValue ($afterCounts -join ',')
		$result | Add-Member -NotePropertyName Integrity -NotePropertyValue $integrity
	}

	$finalDatabaseCounts = Get-TableCounts
	$cleanupStatus = 'PENDING'
	$runSummary = [ordered]@{
		runId = $runId
		k6Version = '2.3.0'
		javaVersion = '21.0.12.1'
		mysqlImage = $image
		mysqlImageId = $initialImageId.Trim()
		mysqlVersion = (Invoke-DbQuery 'SELECT VERSION()')
		apiBaseUrl = $baseUrl
		apiProcessId = $apiProcess.Id
		mysqlContainerId = $containerId
		mysqlHostPort = $databasePort
		database = $databaseName
		flywayMigrations = $migrationResult
		initialDatabaseCounts = $initialCounts
		finalDatabaseCounts = $finalDatabaseCounts
		expectedSettlements = $expectedSettlementCount
		scenarios = @($scenarioResults)
		resourceSamplesFile = $resourcePath
		cleanup = $cleanupStatus
	}
	$runSummary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $runSummaryPath -Encoding UTF8
	Write-Host "Isolated audit DB: $databaseName on loopback port $databasePort; Flyway 3/3 successful."
	Write-Host "Database counts (users,groups,members,expenses,splits,settlements): $finalDatabaseCounts."
	Write-Host "k6 evidence: $reportRoot."
} finally {
	if ($null -ne $apiProcess) {
		if (-not $apiProcess.HasExited) {
			$apiOwners = @(Get-ListenerOwners -Port $ApiPort)
			if ($apiOwners.Count -eq 1 -and $apiOwners[0] -eq [string]$apiProcess.Id) {
				Stop-Process -Id $apiProcess.Id -Force
				$apiProcess.WaitForExit(15000) | Out-Null
			} elseif ($apiOwners.Count -gt 0) {
				$cleanupFailure = "Refused to stop an audit API listener not owned by PID $($apiProcess.Id)."
			} else {
				Stop-Process -Id $apiProcess.Id -Force -ErrorAction SilentlyContinue
			}
		}
		if ($null -ne $apiStdoutTask -and $null -ne $apiStderrTask) {
			[System.IO.File]::WriteAllText($apiStdoutPath, $apiStdoutTask.GetAwaiter().GetResult())
			[System.IO.File]::WriteAllText($apiStderrPath, $apiStderrTask.GetAwaiter().GetResult())
		}
		$apiProcess.Dispose()
	}

	if (-not [string]::IsNullOrWhiteSpace($containerId)) {
		$currentId = (& docker inspect --format '{{.Id}}' $containerName 2>$null)
		if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace([string]$currentId) `
				-and ([string]$currentId).Trim() -eq $containerId) {
			$currentLabels = (& docker inspect --format '{{json .Config.Labels}}' $containerName | ConvertFrom-Json)
			$currentPhase = [string]$currentLabels.'com.splitsmart.audit.phase'
			$currentRun = [string]$currentLabels.'com.splitsmart.audit.run'
			if ($currentPhase -eq '5' -and $currentRun -eq $runId) {
				& docker stop $containerId 2>$null | Out-Null
				if ($LASTEXITCODE -ne 0) {
					$cleanupFailure = 'Could not stop the verified disposable MySQL container.'
				} else {
					& docker rm $containerId 2>$null | Out-Null
					if ($LASTEXITCODE -ne 0) {
						$cleanupFailure = 'Could not remove the verified disposable MySQL container.'
					}
				}
			} else {
				$cleanupFailure = 'Refused to remove a container whose audit ownership labels changed.'
			}
		} elseif ($LASTEXITCODE -eq 0) {
			$cleanupFailure = 'Refused to remove a container whose identity changed.'
		}
	}

	if ($resourceSamples.Count -gt 0) {
		$resourceSamples | Export-Csv -LiteralPath $resourcePath -NoTypeInformation -Encoding UTF8
	}
	$finalListeners = @{
		MySQL = @(Get-ListenerOwners -Port 3306)
		LiveApi = @(Get-ListenerOwners -Port 8081)
		AuditApi = @(Get-ListenerOwners -Port $ApiPort)
	}
	if (($initialListeners.MySQL -join ',') -ne ($finalListeners.MySQL -join ',') `
			-or ($initialListeners.LiveApi -join ',') -ne ($finalListeners.LiveApi -join ',')) {
		$cleanupFailure = 'A protected MySQL or live API listener changed during this run.'
	}
	if ($finalListeners.AuditApi.Count -gt 0) {
		$cleanupFailure = "Audit API port $ApiPort remains occupied after cleanup."
	}
	$finalContainers = @(& docker ps --quiet | Sort-Object)
	if (($initialContainers -join ',') -ne ($finalContainers -join ',')) {
		$cleanupFailure = 'The running Docker container inventory differs from its preflight snapshot.'
	}
	if ((& docker image inspect $image --format '{{.Id}}' 2>$null) -ne $initialImageId.Trim()) {
		$cleanupFailure = 'The cached MySQL image changed unexpectedly.'
	}
	$finalFreeGiB = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
	$cleanupStatus = if ($null -eq $cleanupFailure) { 'PASS' } else { 'FAIL' }
	$cleanupSummary = [ordered]@{
		status = $cleanupStatus
		freeGiBBefore = $initialFreeGiB
		freeGiBAfter = $finalFreeGiB
		protectedMySqlListenersBefore = $initialListeners.MySQL
		protectedMySqlListenersAfter = $finalListeners.MySQL
		protectedApiListenersBefore = $initialListeners.LiveApi
		protectedApiListenersAfter = $finalListeners.LiveApi
		auditApiListenersAfter = $finalListeners.AuditApi
		auditContainerRemoved = if ([string]::IsNullOrWhiteSpace($containerId)) {
			$null
		} else {
			((@(& docker ps --all --quiet --filter "id=$containerId") -join '').Trim().Length -eq 0)
		}
		failure = $cleanupFailure
	}
	$cleanupPath = Join-Path $reportRoot "phase5-$runId-cleanup.json"
	$cleanupSummary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $cleanupPath -Encoding UTF8
	if (Test-Path -LiteralPath $runSummaryPath) {
		$savedSummary = Get-Content -LiteralPath $runSummaryPath -Raw | ConvertFrom-Json
		$savedSummary.cleanup = $cleanupStatus
		$savedSummary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $runSummaryPath -Encoding UTF8
	}
	Write-Host "C: free space $initialFreeGiB GiB before, $finalFreeGiB GiB after. Cleanup: $cleanupStatus."
	if ($null -ne $cleanupFailure) {
		throw $cleanupFailure
	}
}
