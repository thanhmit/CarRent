[CmdletBinding()]
param(
    [switch]$CheckOnly,
    [switch]$NoOpen,
    [switch]$LibraryOnly
)
$ErrorActionPreference = 'Stop'
$script:Issues = [Collections.Generic.List[object]]::new()

function Read-LocalProperties([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }
    $pending = ''
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $text = $pending + $line.TrimStart()
        $slashes = [regex]::Match($text, '\\+$').Length
        if ($slashes % 2 -eq 1) { $pending = $text.Substring(0, $text.Length - 1); continue }
        $pending = ''
        if ($text -match '^\s*[#!]' -or [string]::IsNullOrWhiteSpace($text)) { continue }
        if ($text -notmatch '^\s*((?:\\.|[^\s=:])+)(?:\s*[=:]\s*|\s+)(.*)$') {
            throw 'Unsupported properties syntax; use KEY=value, without shell export or quotes'
        }
        $key = $Matches[1]; $value = $Matches[2]
        $decode = [Text.RegularExpressions.MatchEvaluator]{ param($m)
            switch ($m.Groups[1].Value) {
                't' { "`t" }; 'n' { "`n" }; 'r' { "`r" }; 'f' { "`f" }
                default {
                    if ($m.Groups[1].Value -match '^u[0-9a-fA-F]{4}$') {
                        [string][char][Convert]::ToInt32($m.Groups[1].Value.Substring(1), 16)
                    } else { $m.Groups[1].Value }
                }
            }
        }
        $values[[regex]::Replace($key, '\\(u[0-9a-fA-F]{4}|.)', $decode)] = [regex]::Replace($value, '\\(u[0-9a-fA-F]{4}|.)', $decode)
    }
    if ($pending) { throw 'Incomplete properties continuation' }
    return $values
}

function Get-LocalValue([hashtable]$Properties, [string]$Key, [string]$Default = '') {
    $envKey = $Key.ToUpperInvariant().Replace('.', '_').Replace('-', '')
    $direct = [Environment]::GetEnvironmentVariable($envKey, 'Process')
    if ($null -ne $direct) { $value = $direct }
    elseif ($Properties.ContainsKey($Key)) { $value = [string]$Properties[$Key] }
    else { $value = $Default }
    for ($i = 0; $i -lt 20; $i++) {
        if ($value -notmatch '\$\{') { return $value }
        $next = [regex]::Replace($value, '\$\{([^{}:]+)(?::([^{}]*))?\}', [Text.RegularExpressions.MatchEvaluator]{ param($m)
            $replacement = [Environment]::GetEnvironmentVariable($m.Groups[1].Value, 'Process')
            if ($null -ne $replacement) { return $replacement }
            if ($Properties.ContainsKey($m.Groups[1].Value)) { return [string]$Properties[$m.Groups[1].Value] }
            return $m.Groups[2].Value
        })
        if ($next -eq $value) { throw 'Unresolved or cyclic property reference' }
        $value = $next
    }
    throw 'Too many nested property references'
}

function Get-ProjectProperties([string]$Root) {
    $result = @{}
    foreach ($name in @('src/main/resources/application.properties', 'application-local.properties', '.env')) {
        $part = Read-LocalProperties (Join-Path $Root $name)
        foreach ($key in $part.Keys) { $result[$key] = $part[$key] }
    }
    return $result
}

function ConvertTo-NativeArgument([string]$Value) {
    # Windows CommandLineToArgvW rules, including quotes and trailing backslashes.
    return '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}

function Invoke-LocalProcess([string]$File, [string[]]$Arguments = @(), [string]$InputText = '',
        [hashtable]$Environment = @{}, [int]$TimeoutSeconds = 60) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $File
    $info.Arguments = ($Arguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true; $info.RedirectStandardInput = $true
    $info.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $info.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($key in $Environment.Keys) { $info.EnvironmentVariables[$key] = $Environment[$key] }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
    try {
        [void]$process.Start()
        $out = $process.StandardOutput.ReadToEndAsync(); $err = $process.StandardError.ReadToEndAsync()
        # .NET Framework/PowerShell5.1 has no ProcessStartInfo.StandardInputEncoding.
        $writer = [IO.StreamWriter]::new($process.StandardInput.BaseStream, [Text.UTF8Encoding]::new($false))
        try { if ($InputText) { $writer.Write($InputText) } } finally { $writer.Dispose() }
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill(); $process.WaitForExit()
            return @{ExitCode=124; Output=''; Error='TIMEOUT'}
        }
        return @{ExitCode=$process.ExitCode; Output=$out.Result; Error=$err.Result}
    } finally { $process.Dispose() }
}

function Start-LocalBackground([string]$File, [string[]]$Arguments, [string]$LogPrefix, [hashtable]$ChildEnvironment = @{}) {
    $previous = @{}
    try {
        foreach ($key in $ChildEnvironment.Keys) {
            $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $ChildEnvironment[$key], 'Process')
        }
        return Start-Process -FilePath $File -ArgumentList (($Arguments | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' ') `
            -WorkingDirectory $script:Root -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput ($LogPrefix + '.out.log') -RedirectStandardError ($LogPrefix + '.err.log')
    } finally {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
}

function Add-SetupIssue([string]$Code, [string]$Severity, [string]$Message, [string]$Guide = 'docs/local-startup.md') {
    if (-not ($script:Issues | Where-Object { $_.Code -eq $Code })) {
        $script:Issues.Add([pscustomobject]@{Code=$Code; Severity=$Severity; Message=$Message; Guide=$Guide})
    }
}

function Write-SetupReport([string]$Path, [string]$Root, [hashtable]$Facts) {
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add('# CarRent - startup report / CAC MUC DEV CAN BO SUNG')
    $lines.Add(''); $lines.Add('Updated: ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
    $lines.Add('Guide (Vietnamese): ' + (Join-Path $Root 'docs/local-startup.md'))
    $lines.Add('Private inputs: ' + (Join-Path $Root '.env'))
    $lines.Add('Existing DB/provider overrides: ' + (Join-Path $Root 'application-local.properties'))
    $lines.Add(''); $lines.Add('Do not share this folder, credentials, OTPs, bank details or runtime logs.')
    $lines.Add('No payout or provider login is performed by this launcher.')
    $lines.Add('')
    foreach ($key in ($Facts.Keys | Sort-Object)) { $lines.Add('- ' + $key + ': ' + $Facts[$key]) }
    $lines.Add(''); $lines.Add('## Action items')
    if ($script:Issues.Count -eq 0) { $lines.Add('No startup issues detected. This is not real-money acceptance.') }
    foreach ($issue in $script:Issues) {
        $lines.Add(''); $lines.Add('### [' + $issue.Severity + '] ' + $issue.Code)
        $lines.Add($issue.Message); $lines.Add('Instructions: ' + (Join-Path $Root $issue.Guide))
    }
    $lines.Add(''); $lines.Add('Save the input files, then double-click start.bat again. Existing servers are reused, not restarted.')
    $lines.Add('If configuration changed, stop YOUR CarRent process before starting again to load the new configuration.')
    [IO.File]::WriteAllLines($Path, $lines, [Text.UTF8Encoding]::new($true))
}

function Find-LocalBinary([string]$Name) {
    $command = Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
    $patterns = switch ($Name) {
        'java.exe' { @("$env:JAVA_HOME\bin\java.exe", "$env:ProgramFiles\Eclipse Adoptium\jdk-*\bin\java.exe", "$env:ProgramFiles\Java\jdk-*\bin\java.exe") }
        'mysql.exe' { @("$env:ProgramFiles\MySQL\MySQL Server *\bin\mysql.exe") }
        'ngrok.exe' { @("$env:LOCALAPPDATA\Microsoft\WinGet\Links\ngrok.exe", "$env:ProgramFiles\WinGet\Links\ngrok.exe",
            "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Ngrok.Ngrok*\ngrok.exe", "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Ngrok.Ngrok*\*\ngrok.exe",
            "$env:ProgramFiles\WinGet\Packages\Ngrok.Ngrok*\ngrok.exe") }
    }
    foreach ($pattern in $patterns) {
        $found = Get-Item -Path $pattern -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

function Install-LocalDependency([string]$Package) {
    if ($CheckOnly) { return $false }
    $winget = Find-LocalBinary 'winget.exe'
    if (-not $winget) {
        Write-Host 'Installing Microsoft App Installer (winget). Windows may request normal UAC approval.'
        try {
            $bundle = Join-Path $script:State 'Microsoft.DesktopAppInstaller.msixbundle'
            Invoke-WebRequest 'https://aka.ms/getwinget' -OutFile $bundle -UseBasicParsing -TimeoutSec 120
            # Add-AppxPackage validates the Microsoft package signature; do not bypass it.
            Add-AppxPackage -Path $bundle -ErrorAction Stop
            $winget = Find-LocalBinary 'winget.exe'
        } catch { }
    }
    if (-not $winget) {
        Add-SetupIssue 'WINGET' 'BLOCKED' 'Automatic App Installer setup failed. Install official winget, then run start.bat again.' 'docs/local-startup.md#environment'
        return $false
    }
    Write-Host "Installing $Package via official winget source. This can take several minutes."
    $result = Invoke-LocalProcess $winget @('install','--id',$Package,'--exact','--source','winget','--silent',
        '--accept-package-agreements','--accept-source-agreements','--disable-interactivity') -TimeoutSeconds 900
    [IO.File]::WriteAllText((Join-Path $script:State ('install-' + $Package + '.log')), $result.Output + $result.Error)
    $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User') + ';' + $env:Path
    if ($result.ExitCode -ne 0) {
        Add-SetupIssue ('INSTALL_' + $Package) 'WARN' "Installer exited with code $($result.ExitCode). See the private install log; finish UAC/reboot if requested and retry." 'docs/local-startup.md#environment'
    }
    return ($result.ExitCode -eq 0)
}

function Get-ListeningProcess([int]$Port) {
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $connection) { return $null }
    return Get-CimInstance Win32_Process -Filter ("ProcessId=" + $connection.OwningProcess) -ErrorAction SilentlyContinue
}

function Test-AppProcess([string]$Root, [string]$Name, [string]$CommandLine) {
    $boundary = $Root.TrimEnd('\','/') + '\'
    return ($Name -match '^java(w)?\.exe$' -and $CommandLine -and
        $CommandLine.IndexOf($boundary, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $CommandLine -match '(?i)carrent|com\.carrent\.CarRentApplication')
}

function Test-EmptySchemaBootstrap([string]$Name, [int]$TableCount) {
    return ($Name -ceq 'carrent' -and $TableCount -eq 0)
}

function Get-RequiredColumns([string]$Sql) {
    foreach ($table in [regex]::Matches($Sql, '(?ms)^CREATE TABLE IF NOT EXISTS ([a-z_]+)\s*\((.*?)^\)')) {
        foreach ($column in [regex]::Matches($table.Groups[2].Value, '(?m)^\s+([a-z_]+)\s+(?:BIGINT|TINYINT|SMALLINT|INT|VARCHAR|CHAR|TEXT|LONGTEXT|LONGBLOB|JSON|DATE|DATETIME|TIMESTAMP|DECIMAL|BOOLEAN|BIT|ENUM)\b')) {
            $table.Groups[1].Value + "`t" + $column.Groups[1].Value
        }
    }
}

function Test-NewManagedDatabase([bool]$HadLocal, [hashtable]$Supplied, [string]$Password, [string]$User) {
    if ($HadLocal -or $Password -or $User -ne 'root') { return $false }
    foreach ($name in @('SPRING_DATASOURCE_PASSWORD','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_URL','CARRENT_DB_PASSWORD')) {
        if ($null -ne [Environment]::GetEnvironmentVariable($name, 'Process')) { return $false }
    }
    foreach ($key in $Supplied.Keys) {
        if ($key -like 'spring.datasource.*' -or ($key -like 'CARRENT_DB_*' -and $Supplied[$key])) { return $false }
    }
    return $true
}

function Get-AppStartupArguments([string]$Jar, [hashtable]$Properties) {
    $result = @('-jar',$Jar,'--spring.profiles.active=local','--server.address=127.0.0.1',
        '--spring.jpa.hibernate.ddl-auto=none','--spring.sql.init.mode=never','--carrent.subscription.enabled=true',
        '--carrent.payment.payout.enabled=false','--carrent.rental.deadlines.enabled=false')
    foreach ($role in @('owner','admin')) {
        $id = Get-LocalValue $Properties ('carrent.payment.pilot.' + $role + '-id') (Get-LocalValue $Properties ('CARRENT_SEPAY_' + $role.ToUpperInvariant() + '_ID'))
        $number = [long]0
        if (-not [long]::TryParse($id, [ref]$number) -or $number -lt 1) { $number = 0 }
        $result += '--carrent.payment.pilot.' + $role + '-id=' + $number
        $prefix = 'carrent.payment.providers.sepay.' + $role + '-live.'
        if (-not (Get-LocalValue $Properties ($prefix + 'webhook-secret'))) { $result += '--' + $prefix + 'enabled=false' }
        $prefix = 'carrent.payment.providers.payos.' + $role + '-payout.'
        if (-not (Get-LocalValue $Properties ($prefix + 'client-id')) -or
            -not (Get-LocalValue $Properties ($prefix + 'api-key')) -or
            -not (Get-LocalValue $Properties ($prefix + 'checksum-key'))) { $result += '--' + $prefix + 'enabled=false' }
    }
    if (-not (Get-LocalValue $Properties 'carrent.payment.recipient-lookup.client-id') -or
        -not (Get-LocalValue $Properties 'carrent.payment.recipient-lookup.api-key')) {
        $result += '--carrent.payment.recipient-lookup.enabled=false'
    }
    return $result
}

function Get-SePayInputErrors([hashtable]$Properties) {
    $invalid = @()
    foreach ($field in @('BANK_BIN','ACCOUNT_NUMBER','OWNER_ID','ADMIN_ID')) {
        $value = Get-LocalValue $Properties ('CARRENT_SEPAY_' + $field)
        $pattern = switch ($field) { 'BANK_BIN' {'^[0-9]{6}$'}; 'ACCOUNT_NUMBER' {'^[0-9]{1,19}$'}; default {'^[1-9][0-9]{0,18}$'} }
        $number = [long]0
        if ($value -notmatch $pattern -or ($field -like '*_ID' -and -not [long]::TryParse($value, [ref]$number))) { $invalid += 'CARRENT_SEPAY_' + $field }
    }
    if (-not (Get-LocalValue $Properties 'CARRENT_SEPAY_WEBHOOK_SECRET').Trim()) { $invalid += 'CARRENT_SEPAY_WEBHOOK_SECRET' }
    return $invalid
}

function Invoke-DatabaseQuery([string]$Sql, [string]$Schema = '') {
    $args = @('--protocol=TCP', ('--host=' + $script:DbHost), ('--port=' + $script:DbPort), ('--user=' + $script:DbUser),
        '--connect-timeout=5','--default-character-set=utf8mb4','--batch','--raw','--skip-column-names')
    if ($Schema) { $args += '--database=' + $Schema }
    return Invoke-LocalProcess $script:Mysql $args -InputText $Sql -Environment @{MYSQL_PWD=$script:DbPassword} -TimeoutSeconds 90
}

function Start-OwnedMySql {
    $folder = Join-Path $script:State 'mysql'
    $data = Join-Path $folder 'data'
    [void][IO.Directory]::CreateDirectory($data)
    $daemon = Join-Path (Split-Path $script:Mysql) 'mysqld.exe'
    if (-not (Test-Path -LiteralPath $daemon)) { throw 'MySQL server binary missing' }
    if (-not (Test-Path -LiteralPath (Join-Path $data 'auto.cnf'))) {
        if (@(Get-ChildItem -LiteralPath $data -Force).Count -gt 0) {
            throw 'Incomplete private MySQL initialization; preserve its data and review the guide'
        }
        Write-Host 'Initializing a NEW private MySQL datadir (no existing service/database is modified).'
        # Initialization never serves requests. The init-file sets a password before TCP starts.
        $init = Invoke-LocalProcess $daemon @('--no-defaults','--initialize-insecure','--skip-networking', ('--datadir=' + $data)) -TimeoutSeconds 120
        if ($init.ExitCode -ne 0) { throw 'Private MySQL initialization failed' }
    }
    $args = @('--no-defaults', ('--datadir=' + $data), '--bind-address=127.0.0.1', ('--port=' + $script:DbPort), '--mysqlx=0','--console')
    $initPath = Join-Path $folder 'set-root-password.sql'
    if (Test-Path -LiteralPath $initPath) { $args += '--init-file=' + $initPath }
    $server = Start-LocalBackground $daemon $args (Join-Path $folder 'server')
    [IO.File]::WriteAllText((Join-Path $folder 'server.pid'), [string]$server.Id)
    for ($i=0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        if ($server.HasExited) { break }
        if ((Invoke-DatabaseQuery 'SELECT 1;').ExitCode -eq 0) {
            if (Test-Path -LiteralPath $initPath) { Remove-Item -LiteralPath $initPath -Force }
            return
        }
    }
    throw 'Private MySQL did not become ready; see private server logs'
}

function Find-AppTunnel($Tunnels, [int]$Port) {
    foreach ($tunnel in $Tunnels) {
        try {
            $address = [string]$tunnel.config.addr
            if ($address -match '^\d+$') { $address = 'http://localhost:' + $address }
            elseif ($address -notmatch '://') { $address = 'http://' + $address }
            $upstream = [uri]$address; $public = [uri]$tunnel.public_url
            if ($upstream.Scheme -eq 'http' -and $upstream.Host -in @('localhost','127.0.0.1','[::1]') -and
                $upstream.Port -eq $Port -and $upstream.AbsolutePath -eq '/' -and -not $upstream.UserInfo -and
                -not $upstream.Query -and $public.Scheme -eq 'https' -and $public.Host -and
                -not $public.UserInfo -and $public.AbsolutePath -eq '/' -and -not $public.Query -and -not $public.Fragment) {
                return $public.GetLeftPart([UriPartial]::Authority)
            }
        } catch { }
    }
    return $null
}

function Invoke-LauncherHttp([string]$Url, [hashtable]$Headers = @{}) {
    # No redirects: never forward provider credentials to another origin.
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.Method = 'GET'; $request.AllowAutoRedirect = $false
    $request.Timeout = 8000; $request.ReadWriteTimeout = 8000
    $request.UserAgent = 'CarRent/0.0.1'
    foreach ($key in $Headers.Keys) { $request.Headers[$key] = $Headers[$key] }
    $response = $null
    try { $response = $request.GetResponse() }
    catch [Net.WebException] {
        if ($_.Exception.Response) { $response = $_.Exception.Response }
        else { return @{Status=0; Body=''} }
    }
    try {
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        try { return @{Status=[int]$response.StatusCode; Body=$reader.ReadToEnd()} }
        finally { $reader.Dispose() }
    } finally { if ($response) { $response.Dispose() } }
}

function Get-PayOsReadStatus([int]$Status, [string]$Body) {
    try { $data = $Body | ConvertFrom-Json -ErrorAction Stop } catch { return "HTTP_$Status" }
    if ($Status -eq 403 -and [string]$data.desc -match '\bIP\b') { return 'IP_DENIED' }
    if ($Status -ne 200) { return "HTTP_$Status" }
    if ($data.code -ne '00') { return 'PROVIDER_REJECTED' }
    return 'ACCESSIBLE_UNVERIFIED'
}

function Test-ProviderInputs([hashtable]$Properties, [hashtable]$Facts) {
    $ips = @()
    foreach ($url in @('https://api.ipify.org','https://checkip.amazonaws.com')) {
        $response = Invoke-LauncherHttp $url
        $ip = $null
        if ($response.Status -eq 200 -and [Net.IPAddress]::TryParse($response.Body.Trim(), [ref]$ip) -and
            $ip.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork) { $ips += $ip.ToString() }
    }
    if ($ips.Count -eq 2 -and $ips[0] -eq $ips[1]) { $Facts['Egress IPv4'] = $ips[0] }
    else { Add-SetupIssue 'EGRESS_IP' 'WARN' 'Cannot establish a consistent public IPv4. Check network/VPN and the backend egress before changing payOS allowlist.' 'docs/local-startup.md#payos' }
    foreach ($ref in @('owner-payout','admin-payout')) {
        $prefix = 'carrent.payment.providers.payos.' + $ref + '.'
        $cid = Get-LocalValue $Properties ($prefix + 'client-id')
        $key = Get-LocalValue $Properties ($prefix + 'api-key')
        $checksum = Get-LocalValue $Properties ($prefix + 'checksum-key')
        if (-not $cid -or -not $key -or -not $checksum) {
            Add-SetupIssue ('PAYOS_' + $ref) 'WARN' "Missing payOS payout keys for $ref. Sign in at https://my.payos.vn and fill the matching CARRENT_PAYOS_* fields in .env (or existing local properties)." 'docs/local-startup.md#payos'
            continue
        }
        $read = Invoke-LauncherHttp 'https://api-merchant.payos.vn/v1/payouts-account/balance' @{'x-client-id'=$cid; 'x-api-key'=$key}
        $status = Get-PayOsReadStatus $read.Status $read.Body
        $Facts[('payOS ' + $ref)] = $status
        if ($status -eq 'IP_DENIED') {
            Add-SetupIssue ('PAYOS_' + $ref) 'WARN' 'payOS rejected the backend IP (403). Sign in at https://my.payos.vn, open the payout channel > IP management and add the current Egress IPv4 shown above. ngrok domain is NOT the outbound IP.' 'docs/local-startup.md#payos'
        } elseif ($status -ne 'ACCESSIBLE_UNVERIFIED') {
            Add-SetupIssue ('PAYOS_' + $ref) 'WARN' "payOS GET check: $status. Review payout-channel keys, access and network. No response body or credentials are logged." 'docs/local-startup.md#payos'
        } else {
            Add-SetupIssue ('PAYOS_SIGNED_' + $ref) 'INFO' 'Basic GET access works. Run the existing payos-check profile for signed list/balance verification; this does not prove permission to create payouts.' 'docs/local-startup.md#payos'
        }
    }
    $lookupMissing = @()
    foreach ($entry in @(@('client-id','CARRENT_REFUND_LOOKUP_CLIENT_ID'), @('api-key','CARRENT_REFUND_LOOKUP_API_KEY'))) {
        if (-not (Get-LocalValue $Properties ('carrent.payment.recipient-lookup.' + $entry[0]))) { $lookupMissing += $entry[1] }
    }
    if ($lookupMissing.Count) {
        Add-SetupIssue 'VIETQR_LOOKUP' 'WARN' ('Missing ' + ($lookupMissing -join ', ') + '. Sign in at https://my.vietqr.io and obtain independent lookup entitlement/keys; fill .env. payOS keys cannot replace these keys.') 'docs/local-startup.md#vietqr'
    } elseif ((Get-LocalValue $Properties 'carrent.payment.recipient-lookup.enabled' 'false') -ne 'true') {
        Add-SetupIssue 'VIETQR_DISABLED' 'INFO' 'Lookup keys are present but recipient lookup is disabled. Confirm provider entitlement and use the refund-lookup-check profile before activation.' 'docs/local-startup.md#vietqr'
    } else {
        Add-SetupIssue 'VIETQR_NOT_VERIFIED' 'INFO' 'Lookup is configured. The launcher does not send private recipient data or claim lookup success; run the explicit refund-lookup-check diagnostic.' 'docs/local-startup.md#vietqr'
    }
}

function Start-AppTunnel([hashtable]$Properties, [int]$Port) {
    $response = Invoke-LauncherHttp 'http://127.0.0.1:4040/api/tunnels'
    if ($response.Status -eq 200) {
        try {
            $url = Find-AppTunnel ($response.Body | ConvertFrom-Json).tunnels $Port
            if ($url) { return $url }
        } catch { }
        Add-SetupIssue 'NGROK_BUSY' 'WARN' 'Port 4040 belongs to another/unknown inspector or tunnel. It was not stopped. Configure the correct upstream manually.' 'docs/local-startup.md#ngrok'
        return $null
    }
    if (Get-ListeningProcess 4040) {
        Add-SetupIssue 'NGROK_BUSY' 'WARN' 'Inspector port 4040 is occupied but does not expose a usable tunnel API. No second agent was started; inspect/release the existing port first.' 'docs/local-startup.md#ngrok'
        return $null
    }
    if ($CheckOnly) {
        Add-SetupIssue 'NGROK_STOPPED' 'WARN' 'No matching ngrok tunnel is running. Normal start.bat will attempt to start it when ngrok and its authtoken are available.' 'docs/local-startup.md#ngrok'
        return $null
    }
    $binary = Find-LocalBinary 'ngrok.exe'
    if (-not $binary) { return $null }
    $token = Get-LocalValue $Properties 'CARRENT_NGROK_AUTHTOKEN'
    $existing = @("$env:LOCALAPPDATA\ngrok\ngrok.yml", "$env:USERPROFILE\.config\ngrok\ngrok.yml", "$env:USERPROFILE\.ngrok2\ngrok.yml") |
        Where-Object { Test-Path -LiteralPath $_ }
    if (-not $token -and -not $env:NGROK_AUTHTOKEN -and -not $existing) {
        Add-SetupIssue 'NGROK_TOKEN' 'WARN' 'Sign in at https://dashboard.ngrok.com/get-started/your-authtoken and fill CARRENT_NGROK_AUTHTOKEN in .env. Then run start.bat again; no token goes on the command line.' 'docs/local-startup.md#ngrok'
        return $null
    }
    $childEnv = @{}
    if ($token) { $childEnv['NGROK_AUTHTOKEN'] = $token }
    $args = @('http', ('http://127.0.0.1:' + $Port), '--log=stdout', '--log-format=json')
    $domain = Get-LocalValue $Properties 'CARRENT_PUBLIC_BASE_URL'
    if ($domain) {
        $uri = $null
        if ([uri]::TryCreate($domain, [UriKind]::Absolute, [ref]$uri) -and $uri.Scheme -eq 'https' -and
            -not $uri.UserInfo -and $uri.AbsolutePath -eq '/' -and -not $uri.Query -and -not $uri.Fragment -and $uri.IsDefaultPort) {
            $args += '--domain=' + $uri.Host
        } else {
            Add-SetupIssue 'NGROK_DOMAIN' 'WARN' 'CARRENT_PUBLIC_BASE_URL must be an HTTPS origin without path, credentials or query. Correct it in .env.' 'docs/local-startup.md#ngrok'
            return $null
        }
    }
    $agent = Start-LocalBackground $binary $args (Join-Path $script:State 'ngrok') $childEnv
    [IO.File]::WriteAllText((Join-Path $script:State 'ngrok.pid'), [string]$agent.Id)
    for ($i=0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if ($agent.HasExited) { break }
        $read = Invoke-LauncherHttp 'http://127.0.0.1:4040/api/tunnels'
        if ($read.Status -eq 200) {
            try {
                $url = Find-AppTunnel ($read.Body | ConvertFrom-Json).tunnels $Port
                if ($url) { return $url }
            } catch { }
        }
    }
    Add-SetupIssue 'NGROK_START' 'WARN' 'ngrok did not provide a matching HTTPS tunnel. Check private ngrok logs for expired token, wrong assigned domain, session/quota or network errors.' 'docs/local-startup.md#ngrok'
    return $null
}

function Invoke-LocalLauncher {
    $facts = @{'Mode'=$(if ($CheckOnly) {'CHECK ONLY - no installs/builds/service starts/DB writes'} else {'START'});
        'Payouts'='Launcher never enables payouts; new app starts with payout worker OFF'}
    $report = Join-Path $script:State 'SETUP-NEEDED.md'
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $local = Join-Path $script:Root 'application-local.properties'
        $hadLocal = Test-Path -LiteralPath $local
        if (-not $CheckOnly -and -not (Test-Path -LiteralPath (Join-Path $script:Root '.env'))) {
            Copy-Item -LiteralPath (Join-Path $script:Root '.env.example') -Destination (Join-Path $script:Root '.env')
        }
        $properties = Get-ProjectProperties $script:Root
        $java = Find-LocalBinary 'java.exe'
        $javaReady = $false
        if ($java) {
            $check = Invoke-LocalProcess $java @('-version')
            $javaReady = (($check.Output + $check.Error) -match 'version "(\d+)' -and [int]$Matches[1] -ge 21 -and
                (Test-Path -LiteralPath (Join-Path (Split-Path $java) 'javac.exe')))
        }
        if (-not $javaReady -and -not $CheckOnly) {
            [void](Install-LocalDependency 'EclipseAdoptium.Temurin.21.JDK')
            # Prefer the newly installed Java21 over a stale Java shim in PATH.
            $jdk = Get-Item -Path "$env:ProgramFiles\Eclipse Adoptium\jdk-21*\bin\java.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($jdk) { $java = $jdk.FullName }
            $javaReady = $false
            if ($java) {
                $check = Invoke-LocalProcess $java @('-version')
                $javaReady = (($check.Output + $check.Error) -match 'version "(\d+)' -and [int]$Matches[1] -ge 21 -and
                    (Test-Path -LiteralPath (Join-Path (Split-Path $java) 'javac.exe')))
            }
        }
        if (-not $javaReady) { Add-SetupIssue 'JAVA21' 'BLOCKED' 'A JDK21+ (java AND javac) is required. Normal startup automatically attempts Temurin21 installation.' 'docs/local-startup.md#environment' }
        else { $env:JAVA_HOME = Split-Path (Split-Path $java); $env:Path = (Split-Path $java) + ';' + $env:Path; $facts['Java'] = 'JDK21+ available' }

        $script:Mysql = Find-LocalBinary 'mysql.exe'
        if (-not $script:Mysql -and -not $CheckOnly) { [void](Install-LocalDependency 'Oracle.MySQL'); $script:Mysql = Find-LocalBinary 'mysql.exe' }
        if (-not $script:Mysql) { Add-SetupIssue 'MYSQL8' 'BLOCKED' 'MySQL8 client/server binaries are missing. Normal startup automatically attempts Oracle.MySQL installation.' 'docs/local-startup.md#environment' }
        if (-not (Find-LocalBinary 'ngrok.exe')) {
            if (-not $CheckOnly) {
                [void](Install-LocalDependency 'Ngrok.Ngrok')
                $ngrok = Find-LocalBinary 'ngrok.exe'
                if ($ngrok) {
                    # The winget package can lag the provider's minimum agent version.
                    $update = Invoke-LocalProcess $ngrok @('update') -TimeoutSeconds 120
                    [IO.File]::WriteAllText((Join-Path $script:State 'ngrok-update.log'), $update.Output + $update.Error)
                    if ($update.ExitCode -ne 0) { Add-SetupIssue 'NGROK_UPDATE' 'WARN' 'ngrok self-update failed. Check .local/ngrok-update.log and run ngrok update if the provider rejects an old agent.' 'docs/local-startup.md#ngrok' }
                }
            }
            if (-not (Find-LocalBinary 'ngrok.exe')) { Add-SetupIssue 'NGROK_INSTALL' 'WARN' 'ngrok is missing; local web can run, but public SePay webhook requires ngrok.' 'docs/local-startup.md#ngrok' }
        }
        Test-ProviderInputs $properties $facts
        $dbUrl = Get-LocalValue $properties 'spring.datasource.url'
        if ($dbUrl -notmatch '^jdbc:mysql://(localhost|127\.0\.0\.1)(?::([0-9]{1,5}))?/([a-zA-Z0-9_]+)(?:\?.*)?$') {
            Add-SetupIssue 'DB_URL' 'BLOCKED' 'This launcher supports a local jdbc:mysql://localhost:PORT/SCHEMA URL. Correct application-local.properties; remote DBs are not provisioned.' 'docs/local-startup.md#database'
            return 2
        }
        $script:DbHost=$Matches[1]; $script:DbPort=3306
        if ($Matches[2]) { $script:DbPort=[int]$Matches[2] }
        $schema=$Matches[3]
        if ($script:DbPort -lt 1 -or $script:DbPort -gt 65535) { throw 'Invalid database port' }
        $script:DbUser = Get-LocalValue $properties 'spring.datasource.username' 'root'
        $script:DbPassword = Get-LocalValue $properties 'spring.datasource.password'
        $listener = Get-ListeningProcess $script:DbPort
        $ownedData = Join-Path $script:State 'mysql/data'
        if (-not $CheckOnly -and $script:Mysql -and -not $listener) {
            $services = @(Get-Service -Name 'MySQL*' -ErrorAction SilentlyContinue)
            if ($services.Count -eq 1 -and $services[0].Status -ne 'Running' -and -not (Test-Path -LiteralPath $ownedData)) {
                try { Start-Service -Name $services[0].Name -ErrorAction Stop }
                catch { Add-SetupIssue 'MYSQL_SERVICE' 'BLOCKED' 'Start the existing MySQL service in services.msc with administrator rights, then retry. Its settings/data were not replaced.' 'docs/local-startup.md#database' }
            } elseif ((Test-Path -LiteralPath $ownedData) -or ($services.Count -eq 0 -and
                (Test-NewManagedDatabase $hadLocal (Read-LocalProperties (Join-Path $script:Root '.env')) $script:DbPassword $script:DbUser))) {
                if (-not $hadLocal) {
                    [void][IO.Directory]::CreateDirectory((Split-Path $ownedData))
                    $bytes = New-Object byte[] 24
                    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
                    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
                    $script:DbPassword = 'Aa1!' + ([BitConverter]::ToString($bytes).Replace('-',''))
                    $script:DbUser = 'root'
                    [IO.File]::WriteAllText($local, "spring.datasource.url=$dbUrl`r`nspring.datasource.username=root`r`nspring.datasource.password=$script:DbPassword`r`n", [Text.UTF8Encoding]::new($true))
                    [IO.File]::WriteAllText((Join-Path $script:State 'mysql/set-root-password.sql'), "ALTER USER 'root'@'localhost' IDENTIFIED BY '$script:DbPassword';`r`n", [Text.UTF8Encoding]::new($false))
                }
                Start-OwnedMySql
                $properties = Get-ProjectProperties $script:Root
            }
        }
        $dbReady = $false
        if ($script:Mysql) {
            $connection = Invoke-DatabaseQuery 'SELECT VERSION();'
            if ($connection.ExitCode -eq 0 -and $connection.Output.Trim() -match '^8\.') { $dbReady = $true }
            elseif ($connection.ExitCode -eq 0) { Add-SetupIssue 'MYSQL_VERSION' 'BLOCKED' 'The server must be MySQL8; MariaDB/older/newer incompatible servers are not auto-replaced.' 'docs/local-startup.md#database' }
            else { Add-SetupIssue 'DB_CONNECT' 'BLOCKED' 'Cannot connect to local MySQL. Correct spring.datasource.url/username/password in application-local.properties, or start the existing service. Passwords are not printed.' 'docs/local-startup.md#database' }
        }
        if (-not $dbReady) {
            if (-not $CheckOnly -and -not (Test-Path -LiteralPath $local)) { Copy-Item -LiteralPath (Join-Path $script:Root 'application-local.properties.example') -Destination $local }
            return 2
        }
        $tables = Invoke-DatabaseQuery ("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$schema';")
        if ($tables.ExitCode -ne 0) { throw 'Cannot inspect database metadata' }
        $tableCount = [int]$tables.Output.Trim()
        if (Test-EmptySchemaBootstrap $schema $tableCount) {
            if ($CheckOnly) { Add-SetupIssue 'DB_EMPTY' 'BLOCKED' 'The CarRent schema is absent/empty. Normal startup imports scriptDB.sql only when no tables exist.' 'docs/local-startup.md#database'; return 2 }
            Write-Host 'Importing scriptDB.sql into an ABSENT/EMPTY carrent database only.'
            $seed = Invoke-DatabaseQuery ([IO.File]::ReadAllText((Join-Path $script:Root 'scriptDB.sql')))
            if ($seed.ExitCode -ne 0) { Add-SetupIssue 'DB_IMPORT' 'BLOCKED' 'New schema import failed. Preserve partial data; review DB privileges/SQL compatibility. It is not automatically reset or reimported.' 'docs/local-startup.md#database'; return 2 }
        }
        $columns = Invoke-DatabaseQuery ("SELECT TABLE_NAME,COLUMN_NAME FROM information_schema.columns WHERE table_schema='$schema';")
        if ($columns.ExitCode -ne 0) { throw 'Cannot inspect schema columns' }
        $existing = @($columns.Output.Trim() -split '\r?\n')
        $missing = @(Get-RequiredColumns ([IO.File]::ReadAllText((Join-Path $script:Root 'scriptDB.sql'))) | Where-Object { $_ -notin $existing })
        if ($missing.Count) { Add-SetupIssue 'DB_MIGRATION' 'BLOCKED' "Existing schema is missing $($missing.Count) required columns. Follow docs/migrations/README.md; no bootstrap/migration is run against a nonempty DB." 'docs/local-startup.md#database'; return 2 }
        $facts['Database'] = 'MySQL8 reachable; required table/column names available (not a full migration audit)'

        $portText = Get-LocalValue $properties 'server.port' '8080'
        if ($portText -notmatch '^\d{1,5}$' -or [int]$portText -lt 1 -or [int]$portText -gt 65535) { throw 'Invalid application port' }
        $port = [int]$portText; $origin = 'http://127.0.0.1:' + $port
        $app = Get-ListeningProcess $port
        $alreadyRunning = $false
        if ($app) {
            $health = Invoke-LauncherHttp ($origin + '/cars')
            if ((Test-AppProcess $script:Root $app.Name $app.CommandLine) -and $health.Status -eq 200 -and $health.Body -match 'CarRent') {
                $alreadyRunning=$true; $facts['Application']="Existing app reused at $origin (PID $($app.ProcessId)); its configuration is unchanged"
            } else { Add-SetupIssue 'PORT_BUSY' 'BLOCKED' "Port $port is occupied by another/unverified process. It was not stopped. Release it or change server.port in application-local.properties." 'docs/local-startup.md#startup'; return 2 }
        }
        $tunnel = Start-AppTunnel $properties $port
        if ($tunnel) {
            $facts['Public HTTPS origin'] = $tunnel
            if ((Get-LocalValue $properties 'CARRENT_PUBLIC_BASE_URL') -ne $tunnel) {
                Add-SetupIssue 'PUBLIC_URL' 'WARN' ("Set CARRENT_PUBLIC_BASE_URL=$tunnel in .env and update the SePay webhook if the domain changed.") 'docs/local-startup.md#sepay'
            }
        }
        $channels = Invoke-DatabaseQuery "SELECT id,credential_reference FROM payment_channels WHERE provider='SEPAY' AND purpose='COLLECT' ORDER BY id;" -Schema $schema
        if ($channels.ExitCode -ne 0) { Add-SetupIssue 'SEPAY_CHANNEL_READ' 'WARN' 'Cannot read collection channel mappings; automatic provisioning is skipped. Check DB access/schema.' 'docs/local-startup.md#sepay' }
        if ($channels.ExitCode -eq 0 -and $tunnel) {
            foreach ($row in ($channels.Output.Trim() -split '\r?\n')) {
                if ($row -match '^(\d+)\t(owner-live|admin-live)$') { $facts[('SePay ' + $Matches[2] + ' webhook')] = "$tunnel/webhooks/sepay/$($Matches[1])" }
            }
        }
        $sepayMissing = @(Get-SePayInputErrors $properties)
        if ($sepayMissing.Count) { Add-SetupIssue 'SEPAY_INPUTS' 'WARN' ('Missing or invalid .env fields: ' + ($sepayMissing -join ', ') + '. Obtain HMAC settings at https://my.sepay.vn. These IDs are CarRent users, not bank/provider IDs.') 'docs/local-startup.md#sepay' }
        else {
            $ownerId = [long](Get-LocalValue $properties 'CARRENT_SEPAY_OWNER_ID')
            $adminId = [long](Get-LocalValue $properties 'CARRENT_SEPAY_ADMIN_ID')
            $users = Invoke-DatabaseQuery "SELECT id,role,status FROM users WHERE id IN ($ownerId,$adminId) ORDER BY id;" -Schema $schema
            $userRows = @($users.Output.Trim() -split '\r?\n')
            if ($ownerId -eq $adminId -or $users.ExitCode -ne 0 -or
                "$ownerId`tOWNER`tACTIVE" -notin $userRows -or "$adminId`tADMIN`tACTIVE" -notin $userRows) {
                $sepayMissing += 'CARRENT_SEPAY_OWNER_ID / CARRENT_SEPAY_ADMIN_ID'
                Add-SetupIssue 'SEPAY_USERS' 'WARN' 'The selected users must exist in this DB, be distinct ACTIVE OWNER and ADMIN respectively. Correct CARRENT_SEPAY_OWNER_ID / CARRENT_SEPAY_ADMIN_ID; mappings were not repointed.' 'docs/local-startup.md#sepay'
            }
        }
        Add-SetupIssue 'SEPAY_WEBHOOK' 'INFO' 'The launcher cannot verify/create a webhook inside your SePay account. Configure ONE shared-bank webhook using the Owner channel URL above, HMAC-SHA256, X-SePay-Timestamp and X-SePay-Signature. Check provider delivery history; a tunnel alone does not prove receipt processing.' 'docs/local-startup.md#sepay'
        if ($CheckOnly) {
            if (-not $alreadyRunning) { $facts['Application'] = 'Not running; CheckOnly does not build/start it' }
            return $(if ($script:Issues | Where-Object Severity -eq 'BLOCKED') {2} else {0})
        }
        if (-not $alreadyRunning) {
            if (-not $javaReady) { return 2 }
            Write-Host 'Building using Maven Wrapper (Maven downloads itself on first use).'
            $buildLog = Join-Path $script:State 'build.log'
            $oldPreference = $ErrorActionPreference
            try { $ErrorActionPreference='Continue'; & (Join-Path $script:Root 'mvnw.cmd') '-q' '-DskipTests' 'package' *> $buildLog; $buildExit=$LASTEXITCODE }
            finally { $ErrorActionPreference=$oldPreference }
            if ($buildExit -ne 0) { Add-SetupIssue 'BUILD' 'BLOCKED' 'Maven build failed. See .local/build.log; check JDK21, internet/proxy and the source errors, then retry.' 'docs/local-startup.md#startup'; return 2 }
            $jar = Join-Path $script:Root 'target/carrent-0.0.1-SNAPSHOT.jar'
            if (-not (Test-Path -LiteralPath $jar)) { throw 'Built application JAR is missing' }
            $safeArgs = @(Get-AppStartupArguments $jar $properties)
            if (-not $sepayMissing.Count -and $tunnel -and $channels.ExitCode -eq 0 -and -not $channels.Output.Trim()) {
                Write-Host 'Provisioning missing SePay mappings with the existing DISABLED setup profile.'
                $setupArgs = @('-jar',$jar,'--spring.profiles.active=sepay-setup','--server.port=0','--carrent.payment.maintenance.enabled=false',
                    '--carrent.rental.deadlines.enabled=false','--carrent.payment.payout.enabled=false','--spring.sql.init.mode=never','--spring.jpa.hibernate.ddl-auto=none')
                $setup = Invoke-LocalProcess $java $setupArgs -Environment @{CARRENT_PUBLIC_BASE_URL=$tunnel} -TimeoutSeconds 120
                if ($setup.ExitCode -ne 0) { Add-SetupIssue 'SEPAY_SETUP' 'WARN' 'SePay mapping setup failed; check valid ACTIVE OWNER/ADMIN IDs, bank fields and preexisting mappings. Web can still start.' 'docs/local-startup.md#sepay' }
                else {
                    $created = Invoke-DatabaseQuery "SELECT id,credential_reference FROM payment_channels WHERE provider='SEPAY' AND purpose='COLLECT' ORDER BY id;" -Schema $schema
                    foreach ($row in ($created.Output.Trim() -split '\r?\n')) {
                        if ($row -match '^(\d+)\t(owner-live|admin-live)$') { $facts[('SePay ' + $Matches[2] + ' webhook')] = "$tunnel/webhooks/sepay/$($Matches[1])" }
                    }
                    Add-SetupIssue 'SEPAY_MAPPING_CREATED' 'INFO' 'SePay mappings were created disabled. Use the webhook URLs in this report and the explicit activation procedure in the guide.' 'docs/local-startup.md#sepay'
                }
            }
            $server = Start-LocalBackground $java $safeArgs (Join-Path $script:State 'app')
            [IO.File]::WriteAllText((Join-Path $script:State 'app.pid'), [string]$server.Id)
            $ready = $false
            for ($i=0; $i -lt 90; $i++) {
                Start-Sleep -Milliseconds 500
                if ($server.HasExited) { break }
                $health = Invoke-LauncherHttp ($origin + '/cars')
                if ($health.Status -eq 200 -and $health.Body -match 'CarRent') { $ready=$true; break }
            }
            if (-not $ready) { Add-SetupIssue 'APP_START' 'BLOCKED' 'The new app did not become healthy. See private .local/app.err.log and app.out.log. Correct configuration/source errors and retry; no preexisting app was stopped.' 'docs/local-startup.md#startup'; return 2 }
            $facts['Application']="Running at $origin (PID $($server.Id)); closing the BAT window leaves it running"
        }
        Write-Host ('CarRent: ' + $origin + '/cars')
        if (-not $NoOpen) { Start-Process ($origin + '/cars') }
        return 0
    } catch {
        # Do not print raw exceptions: HTTP/DB/config errors may contain private values.
        $safeError = $_.Exception.GetType().Name + ' at script line ' + $_.InvocationInfo.ScriptLineNumber
        Add-SetupIssue 'STARTUP_ERROR' 'BLOCKED' ("Startup stopped ($safeError). Review the local guide and private logs, correct .env/application-local.properties and retry. Existing processes and data were not deleted.") 'docs/local-startup.md#startup'
        return 2
    } finally {
        Write-SetupReport $report $script:Root $facts
        Write-Host ''; Write-Host ('SETUP REPORT: ' + $report) -ForegroundColor Cyan
        Write-Host ('HUONG DAN:    ' + (Join-Path $script:Root 'docs/local-startup.md'))
        foreach ($issue in $script:Issues) { Write-Host ('[' + $issue.Severity + '] ' + $issue.Code + ': ' + $issue.Message) }
        if (-not $NoOpen -and $script:Issues.Count -gt 0) {
            Start-Process -FilePath 'notepad.exe' -ArgumentList (ConvertTo-NativeArgument $report)
        }
    }
}

if ($LibraryOnly) { return }
$script:Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:State = Join-Path $script:Root '.local'
Set-Location -LiteralPath $script:Root
$newState = -not (Test-Path -LiteralPath $script:State)
[void][IO.Directory]::CreateDirectory($script:State)
if ($newState) {
    # Private MySQL initialization/logs may contain secrets; restrict this new folder.
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $script:State '/inheritance:r' '/grant:r' ("*$($sid):(OI)(CI)F") '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot secure the private .local directory' }
}
$hash = [Security.Cryptography.SHA256]::Create()
try { $identity = [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($script:Root.ToLowerInvariant()))).Replace('-','') }
finally { $hash.Dispose() }
$mutex = [Threading.Mutex]::new($false, ('Local\CarRentLauncher_' + $identity))
$acquired = $false
try {
    try { $acquired = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $acquired = $true }
    if (-not $acquired) {
        Write-Host 'Another launcher for this checkout is already running. Wait for that window; no second startup was attempted.'
        exit 2
    }
    $code = Invoke-LocalLauncher
} finally {
    if ($acquired) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
exit $code
