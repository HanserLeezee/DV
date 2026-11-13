# 给 Windows 通用 WinUSB 驱动的 "ADB Interface" 补上 adb 接口 GUID, 让 adb 能找到手机
$guid = '{F72FE0D4-CBCB-407d-8814-9ED673D0DD6B}'
$devs = Get-PnpDevice -PresentOnly | Where-Object { $_.FriendlyName -match 'ADB' -and $_.InstanceId -like 'USB\*' }
if (-not $devs) { Write-Host '没找到 ADB Interface, 请确认手机已插上且开启了 USB 调试'; exit 1 }
foreach ($d in $devs) {
    $key = 'HKLM:\SYSTEM\CurrentControlSet\Enum\' + $d.InstanceId + '\Device Parameters'
    New-ItemProperty -Path $key -Name 'DeviceInterfaceGUIDs' -PropertyType MultiString -Value @($guid) -Force | Out-Null
    Write-Host ('已写入: ' + $d.InstanceId)
    pnputil /restart-device "$($d.InstanceId)" | Out-Host
}
Start-Sleep 3
& "$PSScriptRoot\adb.exe" kill-server | Out-Null
& "$PSScriptRoot\adb.exe" devices
