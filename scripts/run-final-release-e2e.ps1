param([ValidateSet('smoke', 'all')][string]$Suite = 'smoke')
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$backendRoot = Join-Path $repositoryRoot 'backend'
$frontendRoot = Join-Path $repositoryRoot 'frontend'
$jarPath = Join-Path $backendRoot 'target\splitsmart-backend-0.0.1-SNAPSHOT.jar'
$viteScript = Join-Path $frontendRoot 'node_modules\vite\bin\vite.js'
$runId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$containerName = "splitsmart-final-e2e-$runId"
$volumeName = "splitsmart-final-e2e-$runId"
$apiPort = 18081
$frontendPort = 15173
$apiUrl = "http://127.0.0.1:$apiPort"
$frontendUrl = "http://127.0.0.1:$frontendPort"
$reportDirectory = Join-Path $backendRoot "target\reports\final-release-e2e\$runId"
$apiStdout = Join-Path $reportDirectory 'backend.stdout.log'
$apiStderr = Join-Path $reportDirectory 'backend.stderr.log'
$viteStdout = Join-Path $reportDirectory 'vite.stdout.log'
$viteStderr = Join-Path $reportDirectory 'vite.stderr.log'
$databaseImage = 'mysql:8.4.13'
$managedEnvironment = @(
	'MYSQL_ROOT_PASSWORD',
	'MYSQL_USER',
	'MYSQL_PASSWORD',
	'MYSQL_DATABASE',
	'MYSQL_PWD',
	'DB_URL',
	'DB_USERNAME',
	'DB_PASSWORD',
	'JWT_SECRET',
	'APP_CORS_ALLOWED_ORIGINS',
	'VITE_API_BASE_URL',
	'SPLITSMART_E2E_API_URL',
	'SPLITSMART_E2E_BASE_URL'
)
$previousEnvironment = @{}
$containerCreated = $false
$volumeCreated = $false
$containerRemoved = $false
$volumeRemoved = $false
$apiProcess = $null
$viteProcess = $null
$originalMysqlListeners = $null
$originalApiOwners = @()
$failure = $null
$cleanupIssues = @()

foreach ($name in $managedEnvironment) {
	$previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function New-RandomBytes([int]$length) {
	$bytes = New-Object byte[] $length
	$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
	try {
		$generator.GetBytes($bytes)
	} finally {
		$generator.Dispose()
	}
	return ,$bytes
}

function New-UrlSafeSecret([int]$length) {
	return [Convert]::ToBase64String((New-RandomBytes $length)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Invoke-DockerCommand([string[]]$Arguments, [switch]$AllowFailure) {
	$previousErrorActionPreference = $ErrorActionPreference
	try {
		$ErrorActionPreference = 'Continue'
		$output = & docker @Arguments 2>&1
		$exitCode = $LASTEXITCODE
	} finally {
		$ErrorActionPreference = $previousErrorActionPreference
	}
	if ($exitCode -ne 0) {
		if ($AllowFailure) {
			return $null
		}
		$message = ($output | ForEach-Object { [string]$_ }) -join ' '
		foreach ($secret in @($rootPassword, $applicationPassword, $jwtSecret)) {
			if ($secret) { $message = $message.Replace($secret, '[REDACTED]') }
		}
		throw "Docker $($Arguments[0]) failed (exit $exitCode): $message"
	}
	return ($output -join [Environment]::NewLine).Trim()
}

function Get-DockerLabel([string]$ResourceName, [string]$LabelName, [switch]$Volume) {
	$template = if ($Volume) { '{{json .Labels}}' } else { '{{json .Config.Labels}}' }
	$json = Invoke-DockerCommand @('inspect', '--format', $template, $ResourceName) -AllowFailure
	if (-not $json) {
		return $null
	}
	$labels = ConvertFrom-Json -InputObject $json
	$label = $labels.PSObject.Properties[$LabelName]
	if ($null -eq $label) {
		return $null
	}
	return [string]$label.Value
}

function Invoke-TestDatabaseQuery([string]$Sql, [switch]$AllowFailure) {
	return Invoke-DockerCommand @(
		'exec',
		'--env',
		'MYSQL_PWD',
		$containerName,
		'mysql',
		'--protocol=socket',
		"--user=$env:MYSQL_USER",
		"--database=$env:MYSQL_DATABASE",
		'--batch',
		'--raw',
		'--skip-column-names',
		"--execute=$Sql"
	) -AllowFailure:$AllowFailure
}

function Test-PortAvailable([int]$Port) {
	$connections = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
	return $connections.Count -eq 0
}

function Wait-ForHttp([string]$Url, [System.Diagnostics.Process]$Process, [int]$TimeoutSeconds) {
	$stopAt = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
	while ([DateTime]::UtcNow -lt $stopAt) {
		if ($Process.HasExited) {
			throw 'An isolated test service exited before its readiness check.'
		}
		try {
			$response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
			if ([int]$response.StatusCode -eq 200) {
				return
			}
		} catch {
		}
		Start-Sleep -Seconds 1
	}
	throw 'An isolated test service did not become ready before the timeout.'
}

function Stop-OwnedProcess([System.Diagnostics.Process]$Process, [string]$ExpectedCommandPattern) {
	if ($null -eq $Process) {
		return
	}
	$current = Get-CimInstance Win32_Process -Filter "ProcessId=$($Process.Id)" -ErrorAction SilentlyContinue
	if ($null -eq $current) {
		return
	}
	if ($current.CommandLine -notmatch $ExpectedCommandPattern) {
		$script:cleanupIssues += 'An isolated service PID no longer matched its verified command line.'
		return
	}
	Stop-Process -Id $Process.Id -ErrorAction SilentlyContinue
	$deadline = [DateTime]::UtcNow.AddSeconds(15)
	while ([DateTime]::UtcNow -lt $deadline) {
		$stillRunning = Get-Process -Id $Process.Id -ErrorAction SilentlyContinue
		if ($null -eq $stillRunning) {
			return
		}
		Start-Sleep -Milliseconds 250
	}
	$script:cleanupIssues += 'An isolated service process did not stop within the cleanup timeout.'
}

try {
	if (-not (Test-PortAvailable $apiPort) -or -not (Test-PortAvailable $frontendPort)) {
		throw 'A required isolated E2E port is already in use; no existing listener was stopped.'
	}
	if (-not (Test-Path $jarPath) -or -not (Test-Path $viteScript)) {
		throw 'The packaged backend or installed Vite runner is missing.'
	}
	$dockerInfo = & docker info 2>$null
	if ($LASTEXITCODE -ne 0) {
		throw 'Docker is not responding; the isolated MySQL tier cannot be started.'
	}
	$imageId = Invoke-DockerCommand @('image', 'inspect', '--format', '{{.Id}}', $databaseImage) -AllowFailure
	if (-not $imageId) {
		throw 'The verified MySQL 8.4.13 image is not available locally; no image was pulled.'
	}

	# An absent development server is a valid baseline. Preserve every listener,
	# rather than requiring an unrelated server to be running for isolated tests.
	$originalMysqlListeners = @(Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue |
		ForEach-Object { "$($_.LocalAddress):$($_.LocalPort):$($_.OwningProcess)" } | Sort-Object)
	$originalApiOwners = @(Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)

	$rootPassword = New-UrlSafeSecret 32
	$applicationPassword = New-UrlSafeSecret 32
	$jwtSecret = [Convert]::ToBase64String((New-RandomBytes 32))
	$env:MYSQL_ROOT_PASSWORD = $rootPassword
	$env:MYSQL_USER = 'splitsmart_e2e'
	$env:MYSQL_PASSWORD = $applicationPassword
	$env:MYSQL_DATABASE = 'splitsmart_e2e'
	$env:MYSQL_PWD = $applicationPassword

	$volumeResult = Invoke-DockerCommand @(
		'volume', 'create',
		'--label', 'com.splitsmart.audit.phase=7',
		'--label', "com.splitsmart.audit.run=$runId",
		$volumeName
	)
	if (-not $volumeResult) {
		throw 'The labeled disposable database volume could not be created.'
	}
	$volumeCreated = $true

	$null = Invoke-DockerCommand @(
		'run', '--detach',
		'--name', $containerName,
		'--label', 'com.splitsmart.audit.phase=7',
		'--label', "com.splitsmart.audit.run=$runId",
		'--publish', '127.0.0.1::3306',
		'--mount', "type=volume,source=$volumeName,target=/var/lib/mysql",
		'--env', 'MYSQL_ROOT_PASSWORD',
		'--env', 'MYSQL_USER',
		'--env', 'MYSQL_PASSWORD',
		'--env', 'MYSQL_DATABASE',
		$databaseImage
	)
	$containerCreated = $true

	$databaseReady = $false
	for ($attempt = 0; $attempt -lt 90; $attempt++) {
		$ping = Invoke-DockerCommand @('exec', '--env', 'MYSQL_PWD', $containerName, 'mysqladmin', 'ping', '--host=127.0.0.1', "--user=$env:MYSQL_USER", '--silent') -AllowFailure
		if ($ping -eq 'mysqld is alive') {
			$databaseReady = $true
			break
		}
		Start-Sleep -Seconds 2
	}
	if (-not $databaseReady) {
		throw 'The disposable MySQL service did not accept its isolated application account.'
	}

	$identity = Invoke-TestDatabaseQuery 'SELECT CURRENT_USER(), DATABASE()'
	$identityParts = $identity -split "`t"
	if ($identityParts.Count -ne 2 -or $identityParts[0] -notlike 'splitsmart_e2e@*' -or $identityParts[1] -ne 'splitsmart_e2e') {
		throw 'The E2E database identity did not match its dedicated disposable schema and account.'
	}
	$initialTables = Invoke-TestDatabaseQuery 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()'
	if ($initialTables -ne '0') {
		throw 'The disposable E2E schema was not empty before Flyway startup.'
	}

	$publishedPort = Invoke-DockerCommand @('port', $containerName, '3306/tcp')
	if ($publishedPort -notmatch '^127\.0\.0\.1:(\d+)$') {
		throw 'The disposable MySQL port was not bound exclusively to loopback.'
	}
	$databasePort = [int]$matches[1]
	if ($databasePort -eq 3306) { throw 'Disposable database must not use the development port.' }
	$grants = Invoke-TestDatabaseQuery 'SHOW GRANTS'
	"ISOLATED_DATABASE_GRANTS=$grants"
	foreach ($grant in ($grants -split "[\r\n]+")) {
		if ($grant -notmatch '^GRANT USAGE ON \*\.\* TO ' -and
			$grant -notmatch '^GRANT ALL PRIVILEGES ON `splitsmart\\?_e2e`\.\* TO ') {
			throw 'Unexpected disposable application account privileges.'
		}
	}
	"ISOLATED_DATABASE_PORT=$databasePort"
	"ISOLATED_DATABASE_PRIVILEGES=SCHEMA_ONLY"
	$env:DB_URL = "jdbc:mysql://127.0.0.1:$databasePort/splitsmart_e2e?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true"
	$env:DB_USERNAME = 'splitsmart_e2e'
	$env:DB_PASSWORD = $applicationPassword
	$env:JWT_SECRET = $jwtSecret
	$env:APP_CORS_ALLOWED_ORIGINS = $frontendUrl
	$env:VITE_API_BASE_URL = $apiUrl
	$env:SPLITSMART_E2E_API_URL = $apiUrl
	$env:SPLITSMART_E2E_BASE_URL = $frontendUrl

	New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null
	# Use the real JDK executable: Oracle's PATH shim spawns a child and exits,
	# so its PID cannot prove listener ownership or support deterministic cleanup.
	$java = Join-Path $env:USERPROFILE '.jdks\temurin-21\bin\java.exe'
	if (-not (Test-Path $java)) { throw 'The verified Temurin 21 executable is missing.' }
	$quotedJar = '"' + $jarPath + '"'
	$apiProcess = Start-Process -FilePath $java `
		-WorkingDirectory $backendRoot -WindowStyle Hidden `
		-ArgumentList @('-jar', $quotedJar, "--server.address=127.0.0.1", "--server.port=$apiPort") `
		-PassThru -RedirectStandardOutput $apiStdout -RedirectStandardError $apiStderr
	$apiCommandPattern = [Regex]::Escape($jarPath) + '.*server\.port=' + $apiPort
	$apiCim = Get-CimInstance Win32_Process -Filter "ProcessId=$($apiProcess.Id)" -ErrorAction Stop
	if ($apiCim.CommandLine -notmatch $apiCommandPattern) {
		throw 'The isolated API process command line did not match the expected packaged application and port.'
	}
	Wait-ForHttp "$apiUrl/api/health" $apiProcess 180
	$apiListener = @(Get-NetTCPConnection -LocalPort $apiPort -State Listen)
	if ($apiListener.Count -ne 1 -or $apiListener[0].OwningProcess -ne $apiProcess.Id -or $apiListener[0].LocalAddress -ne '127.0.0.1') { throw 'API listener ownership mismatch.' }

	$emptyCounts = Invoke-TestDatabaseQuery 'SELECT (SELECT COUNT(*) FROM users), (SELECT COUNT(*) FROM expense_groups), (SELECT COUNT(*) FROM group_members), (SELECT COUNT(*) FROM expenses), (SELECT COUNT(*) FROM expense_splits), (SELECT COUNT(*) FROM settlements)'
	if ($emptyCounts -ne "0`t0`t0`t0`t0`t0") {
		throw 'The isolated database contained unexpected data before browser scenarios began.'
	}
	$flywayVersions = Invoke-TestDatabaseQuery "SELECT GROUP_CONCAT(version ORDER BY installed_rank SEPARATOR ',') FROM flyway_schema_history"
	if ($flywayVersions -ne '1,2,3') {
		throw 'The isolated database did not apply exactly the existing Flyway V1-V3 migrations.'
	}
	$flywayChecksums = Invoke-TestDatabaseQuery "SELECT GROUP_CONCAT(checksum ORDER BY installed_rank SEPARATOR ',') FROM flyway_schema_history"
	if ($flywayChecksums -ne '1632565820,-1885547116,-722028687') {
		throw 'The isolated database migration checksums did not match the established V1-V3 baseline.'
	}

	$node = (Get-Command node -ErrorAction Stop).Source
	$quotedViteScript = '"' + $viteScript + '"'
	$viteProcess = Start-Process -FilePath $node `
		-WorkingDirectory $frontendRoot -WindowStyle Hidden `
		-ArgumentList @($quotedViteScript, '--host', '127.0.0.1', '--port', "$frontendPort", '--strictPort') `
		-PassThru -RedirectStandardOutput $viteStdout -RedirectStandardError $viteStderr
	$viteCommandPattern = [Regex]::Escape($viteScript) + '.*--port\s+' + $frontendPort
	$viteCim = Get-CimInstance Win32_Process -Filter "ProcessId=$($viteProcess.Id)" -ErrorAction Stop
	if ($viteCim.CommandLine -notmatch $viteCommandPattern) {
		throw 'The isolated Vite process command line did not match the expected project and loopback port.'
	}
	Wait-ForHttp "$frontendUrl/" $viteProcess 60
	$viteListener = @(Get-NetTCPConnection -LocalPort $frontendPort -State Listen)
	if ($viteListener.Count -ne 1 -or $viteListener[0].OwningProcess -ne $viteProcess.Id -or $viteListener[0].LocalAddress -ne '127.0.0.1') { throw 'Vite listener ownership mismatch.' }

	Push-Location $frontendRoot
	try {
		if ($Suite -eq 'smoke') { & node node_modules/@playwright/test/cli.js test smoke.spec.js }
		else { & node node_modules/@playwright/test/cli.js test }
		$playwrightExitCode = $LASTEXITCODE
	} finally {
		Pop-Location
	}
	if ($playwrightExitCode -ne 0) {
		throw "Playwright exited with code $playwrightExitCode."
	}

	$precisionRows = Invoke-TestDatabaseQuery "SELECT description, CAST(amount AS CHAR) FROM expenses WHERE description LIKE 'Precision-%' ORDER BY id"
	"ISOLATED_PRECISION_ROWS=$precisionRows"
	$finalCounts = Invoke-TestDatabaseQuery 'SELECT (SELECT COUNT(*) FROM users), (SELECT COUNT(*) FROM expense_groups), (SELECT COUNT(*) FROM group_members), (SELECT COUNT(*) FROM expenses), (SELECT COUNT(*) FROM expense_splits), (SELECT COUNT(*) FROM settlements)'
	$finalFlywayVersions = Invoke-TestDatabaseQuery "SELECT GROUP_CONCAT(version ORDER BY installed_rank SEPARATOR ',') FROM flyway_schema_history"
	if ($finalFlywayVersions -ne '1,2,3') {
		throw 'The E2E scenarios altered the disposable Flyway history.'
	}
	$finalFlywayChecksums = Invoke-TestDatabaseQuery "SELECT GROUP_CONCAT(checksum ORDER BY installed_rank SEPARATOR ',') FROM flyway_schema_history"
	if ($finalFlywayChecksums -ne '1632565820,-1885547116,-722028687') {
		throw 'The E2E scenarios altered the disposable Flyway migration checksums.'
	}
	"ISOLATED_E2E_RUN=$runId"
	"ISOLATED_DATABASE_IDENTITY=$identity"
	"ISOLATED_DATABASE_FINAL_COUNTS=$finalCounts"
	"ISOLATED_FLYWAY_VERSIONS=$finalFlywayVersions"
	"PLAYWRIGHT_EXIT_CODE=$playwrightExitCode"
} catch {
	$failure = "Line $($_.InvocationInfo.ScriptLineNumber): $($_.Exception.Message)"
} finally {
	Stop-OwnedProcess $viteProcess ([Regex]::Escape($viteScript) + '.*--port\s+' + $frontendPort)
	Stop-OwnedProcess $apiProcess ([Regex]::Escape($jarPath) + '.*server\.port=' + $apiPort)

	if ($containerCreated) {
		$containerPhase = Get-DockerLabel $containerName 'com.splitsmart.audit.phase'
		$containerRun = Get-DockerLabel $containerName 'com.splitsmart.audit.run'
		if ($containerPhase -eq '7' -and $containerRun -eq $runId) {
			$null = Invoke-DockerCommand @('rm', '--force', $containerName) -AllowFailure
			$containerRemoved = $null -eq (Invoke-DockerCommand @('inspect', '--format', '{{.Id}}', $containerName) -AllowFailure)
		} else {
			$cleanupIssues += 'The disposable database container ownership labels could not be verified.'
		}
		if (-not $containerRemoved) {
			$cleanupIssues += 'The disposable database container remains after cleanup.'
		}
	}
	if ($volumeCreated) {
		$volumeRun = Get-DockerLabel $volumeName 'com.splitsmart.audit.run' -Volume
		if ($volumeRun -eq $runId) {
			$null = Invoke-DockerCommand @('volume', 'rm', $volumeName) -AllowFailure
			$volumeRemoved = $null -eq (Invoke-DockerCommand @('volume', 'inspect', '--format', '{{.Name}}', $volumeName) -AllowFailure)
		} else {
			$cleanupIssues += 'The disposable database volume ownership label could not be verified.'
		}
		if (-not $volumeRemoved) {
			$cleanupIssues += 'The disposable database volume remains after cleanup.'
		}
	}

	if (-not (Test-PortAvailable $apiPort)) {
		$cleanupIssues += 'The isolated API port remains occupied after cleanup.'
	}
	if (-not (Test-PortAvailable $frontendPort)) {
		$cleanupIssues += 'The isolated Vite port remains occupied after cleanup.'
	}
	$mysqlAfter = @(Get-NetTCPConnection -LocalPort 3306 -State Listen -ErrorAction SilentlyContinue |
		ForEach-Object { "$($_.LocalAddress):$($_.LocalPort):$($_.OwningProcess)" } | Sort-Object)
	if ($null -ne $originalMysqlListeners -and ($mysqlAfter -join ',') -ne ($originalMysqlListeners -join ',')) {
		$cleanupIssues += 'The development MySQL listener state changed.'
	}
	$apiOwnersAfter = @(Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
	if ($null -ne $originalMysqlListeners -and ($originalApiOwners -join ',') -ne ($apiOwnersAfter -join ',')) {
		$cleanupIssues += 'Development API listener changed.'
	}

	$logPaths = @($apiStdout, $apiStderr, $viteStdout, $viteStderr)
	$secretValues = @($rootPassword, $applicationPassword, $jwtSecret) | Where-Object { $_ }
	$credentialLeakFound = $false
	foreach ($logPath in $logPaths) {
		if (Test-Path $logPath) {
			$logContent = [string](Get-Content $logPath -Raw)
			foreach ($secret in $secretValues) {
				if (-not [string]::IsNullOrEmpty($logContent) -and $logContent.Contains($secret)) {
					$credentialLeakFound = $true
				}
			}
		}
	}
	if ($credentialLeakFound) {
		$cleanupIssues += 'A test credential was detected in an isolated service log.'
	}
	$reportRoot = Join-Path $frontendRoot 'test-results'
	if (Test-Path $reportRoot) {
		foreach ($reportFile in Get-ChildItem $reportRoot -File -Recurse -ErrorAction SilentlyContinue) {
			if ($reportFile.Extension -in @('.html', '.json', '.xml', '.txt', '.log')) {
				$reportContent = [string](Get-Content $reportFile.FullName -Raw)
				foreach ($secret in $secretValues) {
					if (-not [string]::IsNullOrEmpty($reportContent) -and $reportContent.Contains($secret)) {
						$credentialLeakFound = $true
					}
				}
			}
		}
	}
	if ($credentialLeakFound -and -not ($cleanupIssues -contains 'A test credential was detected in an isolated service log.')) {
		$cleanupIssues += 'A test credential was detected in an ignored E2E report.'
	}
	if ($cleanupIssues.Count -gt 0) {
		$failure = "$failure Cleanup: $($cleanupIssues -join ' ')"
	}

	foreach ($name in $managedEnvironment) {
		$value = $previousEnvironment[$name]
		if ($null -eq $value) {
			[Environment]::SetEnvironmentVariable($name, $null, 'Process')
		} else {
			[Environment]::SetEnvironmentVariable($name, $value, 'Process')
		}
	}
}

if ($failure) {
	Write-Error $failure
	exit 1
}
"ISOLATED_PORTS_FREE=True"
"ORIGINAL_MYSQL_LISTENER_PRESERVED=True"
"ISOLATED_LOG_CREDENTIAL_CHECK=PASS"
"TEST_OWNED_CONTAINER_REMOVED=$containerRemoved"
"TEST_OWNED_VOLUME_REMOVED=$volumeRemoved"
