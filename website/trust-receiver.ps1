param(
    [Parameter(Position = 0)]
    [string]$PhoneIp,
    [Parameter(Position = 1)]
    [int]$Port = 50005
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($PhoneIp)) {
    $PhoneIp = Read-Host "Enter the phone's Wi-Fi IPv4 address"
}

$parsedIp = $null
if (-not [System.Net.IPAddress]::TryParse($PhoneIp, [ref]$parsedIp) -or
    $parsedIp.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
    throw "Enter a valid IPv4 address shown in the receiver app."
}
if ($Port -lt 1 -or $Port -gt 65535) {
    throw "Port must be between 1 and 65535."
}

$client = [System.Net.Sockets.TcpClient]::new()
$stream = $null
try {
    $client.Connect($parsedIp, $Port)
    $validateCertificate = [System.Net.Security.RemoteCertificateValidationCallback]{
        param($sender, $certificate, $chain, $errors)
        return $true
    }
    $stream = [System.Net.Security.SslStream]::new($client.GetStream(), $false, $validateCertificate)
    $stream.AuthenticateAsClient($PhoneIp)
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new(
        $stream.RemoteCertificate.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert)
    )
} catch {
    throw "Could not read the receiver certificate from ${PhoneIp}:$Port. Start listening in the phone app, confirm both devices share Wi-Fi, and try again. Details: $($_.Exception.Message)"
} finally {
    if ($stream) { $stream.Dispose() }
    $client.Dispose()
}

$subjectAltName = $certificate.Extensions | Where-Object { $_.Oid.Value -eq "2.5.29.17" } | Select-Object -First 1
if (-not $subjectAltName -or $subjectAltName.Format($false) -notmatch [regex]::Escape($PhoneIp)) {
    throw "The receiver certificate does not identify $PhoneIp. Check the address and restart listening."
}
if ($certificate.Subject -ne $certificate.Issuer -or
    $certificate.NotBefore -gt (Get-Date) -or
    $certificate.NotAfter -lt (Get-Date)) {
    throw "The receiver did not present a valid self-signed certificate."
}

$store = [System.Security.Cryptography.X509Certificates.X509Store]::new(
    [System.Security.Cryptography.X509Certificates.StoreName]::Root,
    [System.Security.Cryptography.X509Certificates.StoreLocation]::CurrentUser
)
try {
    $store.Open([System.Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
    if ($store.Certificates | Where-Object { $_.Thumbprint -eq $certificate.Thumbprint }) {
        Write-Host "This receiver certificate is already trusted for the current Windows user."
        exit 0
    }

    Write-Host "Phone IP:       $PhoneIp"
    Write-Host "Certificate:    $($certificate.Subject)"
    Write-Host "Valid until:    $($certificate.NotAfter)"
    Write-Host "Thumbprint:     $($certificate.Thumbprint)"
    $answer = Read-Host "Trust this certificate for this Windows user? (Y/N)"
    if ($answer -notmatch "^(?i)y(es)?$") {
        Write-Host "No certificate was installed."
        exit 1
    }

    $store.Add($certificate)
    Write-Host "Certificate trusted. Return to the sender page and connect. If Chrome still reports a certificate error, restart Chrome and try again."
} finally {
    $store.Close()
}