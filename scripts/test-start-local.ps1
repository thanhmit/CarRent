param([string]$Launcher = (Join-Path $PSScriptRoot 'start-local.ps1'), [switch]$MySqlSmoke,
    [string]$AppSmokeJar)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Launcher)) { throw 'Launcher implementation is missing' }
. $Launcher -LibraryOnly
$count = 0
function Check($Condition, [string]$Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:count++
}
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('carrent-launcher-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($fixture)
try {
    $file = Join-Path $fixture 'test.properties'
    [IO.File]::WriteAllText($file, @'
# comment
id=7
alias=${id}
fallback=${CARRENT_TEST_NEVER_SET:ok}
password=a\=b\:c\\d
unicode=Nguy\u1ec5n
continued=hello\
  world
blank=
cycle=${cycle}
'@)
    $props = Read-LocalProperties $file
    Check ((Get-LocalValue $props 'alias') -eq '7') 'placeholder aliases resolve'
    Check ((Get-LocalValue $props 'fallback') -eq 'ok') 'placeholder default resolves'
    Check ((Get-LocalValue $props 'password') -eq 'a=b:c\d') 'Java properties escaping'
    Check ((Get-LocalValue $props 'unicode') -eq "Nguy$([char]0x1ec5)n") 'Java Unicode escapes'
    Check ($props['continued'] -eq 'helloworld') 'continued Java property'
    Check ((Get-LocalValue $props 'blank' 'fallback') -eq '') 'explicit empty value preserved'
    $cycle = $false
    try { Get-LocalValue $props 'cycle' | Out-Null } catch { $cycle = $true }
    Check $cycle 'cyclic placeholders fail without printing their values'
    $env:CARRENT_TEST_LAUNCHER_OVERRIDE = 'from-environment'
    $props['CARRENT_TEST_LAUNCHER_OVERRIDE'] = 'from-file'
    Check ((Get-LocalValue $props 'CARRENT_TEST_LAUNCHER_OVERRIDE') -eq 'from-environment') 'environment wins'
    Remove-Item Env:CARRENT_TEST_LAUNCHER_OVERRIDE

    Check (Test-EmptySchemaBootstrap 'carrent' 0) 'empty CarRent schema may bootstrap'
    Check (-not (Test-EmptySchemaBootstrap 'carrent' 1)) 'nonempty DB never bootstraps'
    Check (-not (Test-EmptySchemaBootstrap 'other' 0)) 'bootstrap cannot target a different schema'
    $schema = "CREATE TABLE IF NOT EXISTS sample (`n    id BIGINT NOT NULL,`n    label VARCHAR(40),`n    CONSTRAINT sample_pk PRIMARY KEY (id)`n);"
    $required = @(Get-RequiredColumns $schema)
    Check ($required.Count -eq 2) 'schema extracts columns and excludes constraints'
    Check ($required -contains "sample`tid") 'schema identifies required column'
    Check (@(Get-RequiredColumns "CREATE TABLE IF NOT EXISTS stateful (`n    status ENUM('A','B') NOT NULL`n);") -contains "stateful`tstatus") 'schema checks ENUM columns too'

    $startup = @(Get-AppStartupArguments 'C:\fixture\carrent.jar' @{'CARRENT_SEPAY_OWNER_ID'='';'CARRENT_SEPAY_ADMIN_ID'='bad';'carrent.payment.recipient-lookup.enabled'='true'})
    Check ($startup -contains '--carrent.payment.pilot.owner-id=0') 'fresh blank pilot owner does not prevent web boot'
    Check ($startup -contains '--carrent.payment.pilot.admin-id=0') 'invalid pilot admin fails closed to no pilot'
    Check ($startup -contains '--carrent.payment.recipient-lookup.enabled=false') 'incomplete lookup does not prevent web boot'
    Check ($startup -contains '--carrent.payment.payout.enabled=false') 'new web disables payout worker by default'
    $liveStartup = @(Get-AppStartupArguments 'C:\fixture\carrent.jar' @{'carrent.payment.payout.enabled'='true'})
    Check ($liveStartup -contains '--carrent.payment.payout.enabled=true') 'explicit configuration enables payout worker'
    Check ($liveStartup -notcontains '--carrent.payment.payout.enabled=false') 'explicit payout activation is not overridden'
    $invalidStartup = @(Get-AppStartupArguments 'C:\fixture\carrent.jar' @{'carrent.payment.payout.enabled'='invalid'})
    Check ($invalidStartup -contains '--carrent.payment.payout.enabled=false') 'invalid payout flag stays disabled'
    Check ($startup -contains '--carrent.payment.providers.sepay.owner-live.enabled=false') 'missing collection secret disables incomplete provider'
    Check (-not (Test-NewManagedDatabase $false @{'spring.datasource.password'='supplied'} 'supplied' 'root')) 'existing effective credentials are never replaced'
    Check (-not (Test-NewManagedDatabase $false @{'spring.datasource.username'='developer'} '' 'developer')) 'existing DB username is never replaced'
    Check (Test-NewManagedDatabase $false @{} '' 'root') 'fresh unconfigured DB can be managed'

    $tunnels = @(
        @{public_url='https://wrong.example';config=@{addr='http://127.0.0.1:9090'}},
        @{public_url='https://right.example';config=@{addr='http://localhost:8080'}},
        @{public_url='https://user:pass@bad.example';config=@{addr='http://localhost:8080'}}
    )
    Check ((Find-AppTunnel $tunnels 8080) -eq 'https://right.example') 'tunnel must target this app'
    Check (-not (Find-AppTunnel @(@{public_url='http://insecure.example';config=@{addr='8080'}}) 8080)) 'public tunnel requires HTTPS'
    Check (-not (Find-AppTunnel @(@{public_url='https://wrong.example';config=@{addr='http://remote.example:8080'}}) 8080)) 'tunnel to a remote app is rejected'
    Check (-not (Find-AppTunnel @(@{public_url='https://wrong.example';config=@{addr='http://localhost:8080/other'}}) 8080)) 'upstream paths are not equivalent'
    Check ((Get-PayOsReadStatus 403 '{"code":"403","desc":"IP is not allowed"}') -eq 'IP_DENIED') 'payOS IP denial classified'
    Check ((Get-PayOsReadStatus 403 '<html>Cloudflare</html>') -eq 'HTTP_403') 'Cloudflare is not called an IP allowlist denial'
    Check ((Get-PayOsReadStatus 200 '{"code":"00","data":{}}') -eq 'ACCESSIBLE_UNVERIFIED') 'unsigned response is not VERIFIED'
    Check ((Get-PayOsReadStatus 200 '{"code":"401","desc":"bad keys"}') -eq 'PROVIDER_REJECTED') 'HTTP200 provider error fails'
    Check ((Get-PayOsReadStatus 302 '') -eq 'HTTP_302') 'provider redirects not accepted'

    Check ((ConvertTo-NativeArgument 'C:\a b\') -eq '"C:\a b\\"') 'native quoting doubles trailing slash'
    Check ((ConvertTo-NativeArgument 'a"b') -eq '"a\"b"') 'native quoting escapes embedded quote'
    Check (Test-AppProcess 'C:\Projects\Car' 'java.exe' 'java -jar "C:\Projects\Car\target\carrent-0.0.1-SNAPSHOT.jar"') 'owned Java app identified'
    Check (-not (Test-AppProcess 'C:\Projects\Car' 'java.exe' 'java -jar C:\Projects\CarOther\carrent.jar')) 'neighboring repo not owned'
    Check (-not (Test-AppProcess 'C:\Projects\Car' 'node.exe' 'node C:\Projects\Car\carrent.js')) 'non-Java process not owned'

    $script:Issues = [Collections.Generic.List[object]]::new()
    Add-SetupIssue 'PAYOS_OWNER' 'WARN' 'PAYOS IP_DENIED' 'docs/local-startup.md#payos'
    $path = Join-Path $fixture 'SETUP-NEEDED.md'
    Write-SetupReport $path $fixture @{'Egress IPv4'='203.0.113.7'}
    $report = [IO.File]::ReadAllText($path)
    Check ($report.Contains('PAYOS IP_DENIED')) 'report contains actionable safe issue'
    Check ($report.Contains((Join-Path $fixture 'docs/local-startup.md#payos'))) 'report includes guide path'
    Check (-not $report.Contains('a=b:c\d')) 'report never dumps config values'
    Check ($report.Contains('203.0.113.7')) 'report includes current operational IP'

    $shell = Join-Path $PSHOME 'powershell.exe'
    $result = Invoke-LocalProcess $shell @('-NoProfile','-NonInteractive','-Command','exit 7') -TimeoutSeconds 5
    Check ($result.ExitCode -eq 7) 'native process propagates nonzero exit'
    $result = Invoke-LocalProcess $shell @('-NoProfile','-NonInteractive','-Command','[Console]::Write($env:CARRENT_TEST_CHILD_SECRET)') -Environment @{CARRENT_TEST_CHILD_SECRET='private-child'} -TimeoutSeconds 5
    Check ($result.Output.Contains('private-child')) 'child-only environment supported'
    Check (-not $env:CARRENT_TEST_CHILD_SECRET) 'child secret not left in parent environment'
    $unicodeInput = 'Nguy' + [char]0x1ec5 + 'n'
    $echo = '[Console]::InputEncoding=[Text.UTF8Encoding]::new($false);[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false);[Console]::Write([Console]::In.ReadToEnd())'
    $result = Invoke-LocalProcess $shell @('-NoProfile','-NonInteractive','-Command',$echo) -InputText $unicodeInput -TimeoutSeconds 5
    Check ($result.Output -eq $unicodeInput) 'Vietnamese SQL input remains UTF8'
    $inputErrors = @(Get-SePayInputErrors @{'CARRENT_SEPAY_BANK_BIN'='bad';'CARRENT_SEPAY_ACCOUNT_NUMBER'='123x';'CARRENT_SEPAY_OWNER_ID'='9223372036854775808';'CARRENT_SEPAY_ADMIN_ID'='0'})
    Check ($inputErrors -contains 'CARRENT_SEPAY_BANK_BIN') 'invalid bank BIN is identified'
    Check ($inputErrors -contains 'CARRENT_SEPAY_ACCOUNT_NUMBER') 'invalid account is identified without printing value'
    Check ($inputErrors -contains 'CARRENT_SEPAY_OWNER_ID') 'overflowing user ID is identified'
    Check ($inputErrors -contains 'CARRENT_SEPAY_ADMIN_ID') 'zero user ID is identified'

    function Invoke-LauncherScenario([string]$Mode) {
        $savedPath=$env:Path; $savedJavaHome=$env:JAVA_HOME
        $scenarioRoot = Join-Path $fixture ('project ' + $Mode)
        [void][IO.Directory]::CreateDirectory((Join-Path $scenarioRoot 'src/main/resources'))
        [void][IO.Directory]::CreateDirectory((Join-Path $scenarioRoot '.local'))
        [void][IO.Directory]::CreateDirectory((Join-Path $scenarioRoot 'bin'))
        $repo = Split-Path $PSScriptRoot
        Copy-Item (Join-Path $repo 'scriptDB.sql') (Join-Path $scenarioRoot 'scriptDB.sql')
        Copy-Item (Join-Path $repo 'src/main/resources/application.properties') (Join-Path $scenarioRoot 'src/main/resources/application.properties')
        [IO.File]::WriteAllText((Join-Path $scenarioRoot 'application-local.properties'), 'spring.datasource.password=fixture-only')
        if ($Mode -eq 'wrong-users') {
            [IO.File]::WriteAllText((Join-Path $scenarioRoot '.env'), "CARRENT_SEPAY_BANK_BIN=970423`nCARRENT_SEPAY_ACCOUNT_NUMBER=000123456`nCARRENT_SEPAY_WEBHOOK_SECRET=fixture-not-real`nCARRENT_SEPAY_OWNER_ID=2`nCARRENT_SEPAY_ADMIN_ID=1`n")
        }
        $fakeJava = Join-Path $scenarioRoot 'bin/java.exe'
        [IO.File]::WriteAllText($fakeJava, '')
        [IO.File]::WriteAllText((Join-Path $scenarioRoot 'bin/javac.exe'), '')
        $script:Root=$scenarioRoot; $script:State=Join-Path $scenarioRoot '.local'
        $script:Issues=[Collections.Generic.List[object]]::new()
        $script:Scenario=@{Queries=[Collections.Generic.List[string]]::new();Starts=0;Installs=0}
        $CheckOnly=$true; $NoOpen=$true
        function Find-LocalBinary([string]$Name) { return $fakeJava }
        function Invoke-LocalProcess { return @{ExitCode=0;Output='';Error='openjdk version "21.0.9"'} }
        function Install-LocalDependency { $script:Scenario.Installs++; throw 'Doctor attempted installation' }
        function Start-LocalBackground { $script:Scenario.Starts++; throw 'Doctor attempted process start' }
        function Get-ListeningProcess {
            param([int]$Port)
            if ($Port -eq 3306) { return @{Name='mysqld.exe';ProcessId=123} }
            if ($Mode -eq 'port-busy') { return @{Name='node.exe';CommandLine='unrelated';ProcessId=456} }
            return @{Name='java.exe';CommandLine=('java -jar "' + $scenarioRoot + '\target\carrent.jar"');ProcessId=789}
        }
        function Test-ProviderInputs { }
        function Invoke-LauncherHttp { return @{Status=200;Body='<title>CarRent</title>'} }
        function Start-AppTunnel { return 'https://fixture.example' }
        function Invoke-DatabaseQuery([string]$Sql, [string]$Schema='') {
            $script:Scenario.Queries.Add($Sql)
            if ($Sql -eq 'SELECT VERSION();') { return @{ExitCode=0;Output='8.0.42'} }
            if ($Sql -match 'COUNT\(\*\)') {
                return @{ExitCode=0;Output=$(if($Mode -eq 'empty'){'0'}else{'20'})}
            }
            if ($Sql -match 'information_schema.columns') {
                $rows = Get-RequiredColumns ([IO.File]::ReadAllText((Join-Path $scenarioRoot 'scriptDB.sql')))
                return @{ExitCode=0;Output=$(if($Mode -eq 'incompatible'){'users' + "`tid"}else{$rows -join "`n"})}
            }
            if ($Sql -match 'FROM payment_channels') {
                if ($Schema -eq 'carrent' -and $Sql.Contains("purpose='COLLECT'")) {
                    return @{ExitCode=0;Output="3`towner-live`n4`tadmin-live`n"}
                }
                return @{ExitCode=1;Output='';Error='NO_DATABASE_OR_WRONG_PURPOSE'}
            }
            if ($Sql -match 'FROM users') { return @{ExitCode=0;Output="1`tADMIN`tACTIVE`n2`tCUSTOMER`tACTIVE"} }
            throw 'Doctor attempted unexpected/mutating query'
        }
        try {
            $result = Invoke-LocalLauncher
            return @{Result=$result;Report=[IO.File]::ReadAllText((Join-Path $script:State 'SETUP-NEEDED.md'));State=$script:Scenario}
        } finally { $env:Path=$savedPath; $env:JAVA_HOME=$savedJavaHome }
    }
    $scenario = Invoke-LauncherScenario 'existing'
    Check ($scenario.Result -eq 0) 'doctor can reuse healthy owned app'
    Check ($scenario.Report.Contains('https://fixture.example/webhooks/sepay/3')) 'doctor reports webhook from actual COLLECT channel and selected schema'
    Check ($scenario.State.Starts -eq 0 -and $scenario.State.Installs -eq 0) 'doctor never starts or installs'
    $scenario = Invoke-LauncherScenario 'empty'
    Check ($scenario.Result -eq 2 -and $scenario.Report.Contains('DB_EMPTY')) 'doctor reports absent schema without importing'
    $scenario = Invoke-LauncherScenario 'incompatible'
    Check ($scenario.Result -eq 2 -and $scenario.Report.Contains('DB_MIGRATION')) 'nonempty incompatible DB is never bootstrapped'
    $scenario = Invoke-LauncherScenario 'port-busy'
    Check ($scenario.Result -eq 2 -and $scenario.Report.Contains('PORT_BUSY')) 'foreign process is not reused or stopped'
    $scenario = Invoke-LauncherScenario 'wrong-users'
    Check ($scenario.Result -eq 0 -and $scenario.Report.Contains('SEPAY_USERS')) 'wrong user roles reported without blocking local web'
    if ($MySqlSmoke) {
        $script:Root = Join-Path $fixture 'isolated mysql'
        $script:State = Join-Path $script:Root '.local'
        [void][IO.Directory]::CreateDirectory((Join-Path $script:State 'mysql'))
        $script:Mysql = Find-LocalBinary 'mysql.exe'
        if (-not $script:Mysql) { throw 'Smoke test requires existing MySQL8 binaries; it never installs them' }
        $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $probe.Start(); $script:DbPort = $probe.LocalEndpoint.Port; $probe.Stop()
        $script:DbHost='127.0.0.1'; $script:DbUser='root'
        $script:DbPassword='FixtureOnly-Aa1!' + [guid]::NewGuid().ToString('N')
        $smokeData = Join-Path $script:State 'mysql/data'
        [IO.File]::WriteAllText((Join-Path $script:State 'mysql/set-root-password.sql'), "ALTER USER 'root'@'localhost' IDENTIFIED BY '$script:DbPassword';`n", [Text.UTF8Encoding]::new($false))
        Start-OwnedMySql
        Check ((Invoke-DatabaseQuery 'SELECT 1;').ExitCode -eq 0) 'new private MySQL authenticates with generated password'
        $noPassword = Invoke-LocalProcess $script:Mysql @('--protocol=TCP','--host=127.0.0.1',('--port='+$script:DbPort),'--user=root','--connect-timeout=3') -InputText 'SELECT 1;' -Environment @{MYSQL_PWD=''}
        Check ($noPassword.ExitCode -ne 0) 'new MySQL never accepts blank password after startup'
        $seed = Invoke-DatabaseQuery ([IO.File]::ReadAllText((Join-Path (Split-Path $PSScriptRoot) 'scriptDB.sql')))
        Check ($seed.ExitCode -eq 0) 'real bootstrap succeeds on isolated fresh server'
        $tables = Invoke-DatabaseQuery "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='carrent';"
        Check ([int]$tables.Output.Trim() -eq 20) 'bootstrap creates all twenty tables'
        $utf8Query = "SELECT '$unicodeInput';"
        $utf8 = Invoke-DatabaseQuery $utf8Query
        Check ($utf8.Output.Trim() -eq $unicodeInput) 'real MySQL SQL input/output preserves Vietnamese'
        Check (-not (Test-Path (Join-Path $script:State 'mysql/set-root-password.sql'))) 'initial password SQL removed after successful authentication'
        if ($AppSmokeJar) {
            $jarSource = (Resolve-Path -LiteralPath $AppSmokeJar).Path
            [void][IO.Directory]::CreateDirectory((Join-Path $script:Root 'target'))
            [void][IO.Directory]::CreateDirectory((Join-Path $script:Root 'src/main/resources'))
            Copy-Item $jarSource (Join-Path $script:Root 'target/carrent-0.0.1-SNAPSHOT.jar')
            $repo = Split-Path $PSScriptRoot
            Copy-Item (Join-Path $repo 'src/main/resources/application.properties') (Join-Path $script:Root 'src/main/resources/application.properties')
            Copy-Item (Join-Path $repo 'scriptDB.sql') (Join-Path $script:Root 'scriptDB.sql')
            Copy-Item (Join-Path $repo '.env.example') (Join-Path $script:Root '.env')
            # Exercise startup using an already-built JAR, without downloading/building dependencies in this fixture.
            [IO.File]::WriteAllText((Join-Path $script:Root 'mvnw.cmd'), "@exit /b 0`r`n")
            $probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
            $probe.Start(); $appPort = $probe.LocalEndpoint.Port; $probe.Stop()
            $config = "spring.datasource.url=jdbc:mysql://127.0.0.1:$($script:DbPort)/carrent?useSSL=false&allowPublicKeyRetrieval=true`nspring.datasource.username=root`nspring.datasource.password=$script:DbPassword`nserver.port=$appPort`n"
            [IO.File]::WriteAllText((Join-Path $script:Root 'application-local.properties'), $config)
            $CheckOnly=$false; $NoOpen=$true; $script:Issues=[Collections.Generic.List[object]]::new()
            Push-Location -LiteralPath $script:Root
            try { $launchResult = Invoke-LocalLauncher } finally { Pop-Location }
            Check ($launchResult -eq 0) 'new isolated app starts with blank payment configuration'
            $response = Invoke-LauncherHttp ("http://127.0.0.1:$appPort/cars")
            Check ($response.Status -eq 200 -and $response.Body.Contains('CarRent')) 'new app serves actual CarRent HTML'
            $appCommand = (Get-CimInstance Win32_Process -Filter ('ProcessId=' + [IO.File]::ReadAllText((Join-Path $script:State 'app.pid')))).CommandLine
            Check ($appCommand.Contains('--carrent.payment.pilot.owner-id=0')) 'new app receives safe zero pilot ID'
            Check ($appCommand.Contains('--carrent.payment.payout.enabled=false')) 'new app receives disabled payout worker'
        }
    }
    Write-Host "PASS: $count launcher checks"
} finally {
    if ($AppSmokeJar -and $script:State) {
        $appPid = Join-Path $script:State 'app.pid'
        if (Test-Path -LiteralPath $appPid) {
            $ownedAppId=[int]([IO.File]::ReadAllText($appPid))
            $ownedApp=Get-CimInstance Win32_Process -Filter ('ProcessId=' + $ownedAppId) -ErrorAction SilentlyContinue
            if ($ownedApp -and (Test-AppProcess $script:Root $ownedApp.Name $ownedApp.CommandLine)) {
                Stop-Process -Id $ownedAppId -Force
                Wait-Process -Id $ownedAppId -Timeout 10 -ErrorAction SilentlyContinue
            } elseif ($ownedApp) { throw 'App fixture PID no longer owned; refusing cleanup' }
        }
    }
    if ($smokeData) {
        $pidFile = Join-Path $script:State 'mysql/server.pid'
        if (Test-Path -LiteralPath $pidFile) {
            $ownedId = [int]([IO.File]::ReadAllText($pidFile))
            $owned = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $ownedId) -ErrorAction SilentlyContinue
            if ($owned -and $owned.Name -eq 'mysqld.exe' -and $owned.CommandLine.Contains($smokeData)) {
                $admin = Join-Path (Split-Path $script:Mysql) 'mysqladmin.exe'
                $stop = Invoke-LocalProcess $admin @('--protocol=TCP','--host=127.0.0.1',('--port='+$script:DbPort),'--user=root','shutdown') -Environment @{MYSQL_PWD=$script:DbPassword} -TimeoutSeconds 20
                if ($stop.ExitCode -ne 0) { throw 'Could not cleanly stop the owned MySQL smoke server; preserve fixture for recovery' }
                Wait-Process -Id $ownedId -Timeout 15 -ErrorAction SilentlyContinue
                if (Get-Process -Id $ownedId -ErrorAction SilentlyContinue) { throw 'MySQL smoke process still running; refusing cleanup' }
            } elseif ($owned) { throw 'Fixture PID no longer owned; refusing process or directory cleanup' }
        }
    }
    if ($env:CARRENT_TEST_LAUNCHER_OVERRIDE) { Remove-Item Env:CARRENT_TEST_LAUNCHER_OVERRIDE }
    $resolved = [IO.Path]::GetFullPath($fixture)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
