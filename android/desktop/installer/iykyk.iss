; IYKYK for Windows: the desktop app (Compose, from :desktop:createDistributable)
; plus the core service (iykyk-service.exe), which keeps the VPN and needs
; administrator rights once, at installation.
;
; Built in CI: iscc /DAppImage=... /DCoreDir=... /DAppVersion=... iykyk.iss

#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif

[Setup]
AppId={{6D3C3F5E-8F0E-4B8E-9A51-1F0B2A7C9E41}
AppName=IYKYK
AppVersion={#AppVersion}
AppPublisher=IYKYK
AppPublisherURL=https://github.com/iykyk-dev/iykyk
DefaultDirName={autopf}\IYKYK
DefaultGroupName=IYKYK
DisableProgramGroupPage=yes
OutputBaseFilename=IYKYK-Setup
SetupIconFile=..\icon.ico
UninstallDisplayIcon={app}\IYKYK.exe
Compression=lzma2/ultra64
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=admin
MinVersion=10.0
CloseApplications=yes
WizardStyle=modern
LicenseFile=..\..\..\LICENSE

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
ru.SetupService=Настраиваю службу IYKYK…
en.SetupService=Setting up the IYKYK service…

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion
Source: "{#CoreDir}\iykyk-service.exe"; DestDir: "{app}\core"; Flags: ignoreversion
Source: "{#CoreDir}\rulesets\*"; DestDir: "{app}\core\rulesets"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\IYKYK"; Filename: "{app}\IYKYK.exe"
Name: "{autodesktop}\IYKYK"; Filename: "{app}\IYKYK.exe"; Tasks: desktopicon

[Run]
; Registers and starts the core service (replaces an older one on upgrade).
Filename: "{app}\core\iykyk-service.exe"; Parameters: "install"; Flags: runhidden waituntilterminated; StatusMsg: "{cm:SetupService}"
Filename: "{app}\IYKYK.exe"; Description: "{cm:LaunchProgram,IYKYK}"; Flags: nowait postinstall skipifsilent
; An update started from the app (/SILENT): start the new version as the user.
Filename: "{app}\IYKYK.exe"; Flags: nowait runasoriginaluser; Check: WizardSilent

[UninstallRun]
Filename: "{app}\core\iykyk-service.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveService"

[Code]
// Stop the service before files are replaced on an upgrade.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  if FileExists(ExpandConstant('{app}\core\iykyk-service.exe')) then
    Exec(ExpandConstant('{app}\core\iykyk-service.exe'), 'uninstall', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := '';
end;
