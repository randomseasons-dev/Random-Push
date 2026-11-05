; Random Push installer hooks.
; 1. Replaces an older "Data Push" install (the app's old name): stops it, keeps its
;    auto-run setting, runs its uninstaller silently. Settings and history stay (same app id).
; 2. Allows Random Push through Windows Firewall (LAN discovery UDP 47321 + transfers TCP 47322).

Var OldAutoRun

!macro NSIS_HOOK_PREINSTALL
  StrCpy $OldAutoRun ""
  ReadRegStr $OldAutoRun HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "Data Push"
  nsExec::Exec 'taskkill /F /IM "Data Push.exe"'
  ReadRegStr $R0 HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Data Push" "InstallLocation"
  StrCmp $R0 "" dp_old_done
    StrCpy $R1 $R0 "" 1
    StrCpy $R1 $R1 -1
    IfFileExists "$R1\uninstall.exe" 0 dp_old_done
      ExecWait '"$R1\uninstall.exe" /S _?=$R1'
      Delete "$R1\uninstall.exe"
      RMDir "$R1"
  dp_old_done:
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\Data Push"
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Data Push"'
!macroend

!macro NSIS_HOOK_POSTINSTALL
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Random Push"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="Random Push" dir=in action=allow program="$INSTDIR\Random Push.exe" enable=yes profile=any'
  StrCmp $OldAutoRun "" dp_run_done
    DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "Data Push"
    WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "Random Push" '"$INSTDIR\Random Push.exe" --minimized'
  dp_run_done:
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Random Push"'
!macroend
