[Setup]
AppName=UpaLive Automation
AppVersion=1.0
DefaultDirName={autopf}\UpaLive
DefaultGroupName=UpaLive Automation
OutputDir=.\Output
OutputBaseFilename=UpaLive_Setup
Compression=lzma
SolidCompression=yes
PrivilegesRequired=lowest
SetupIconFile=release_package\UpaLive_Icon.ico

[Files]
Source: "release_package\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autodesktop}\UpaLive"; Filename: "{app}\start.bat"; IconFilename: "{app}\UpaLive_Icon.ico"
Name: "{group}\UpaLive"; Filename: "{app}\start.bat"; IconFilename: "{app}\UpaLive_Icon.ico"