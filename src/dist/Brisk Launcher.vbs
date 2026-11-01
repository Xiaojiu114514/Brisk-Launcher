Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = dir
' Brisk Launcher entry script
sh.Run "cmd /c """"" & dir & "\Brisk Launcher Debug.cmd""""", 0, False
