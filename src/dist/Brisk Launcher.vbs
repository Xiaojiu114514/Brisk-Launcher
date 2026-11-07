' Brisk Launcher entry script (no console window)
'
' Fast path: data\launch.txt is written by launch.ps1 (first run / Java repair).
'   #v=1              format marker
'   #dir=<dir>        install dir this file was generated for (moved folder -> fallback)
'   <javaw path>      JVM executable
'   <arg>...          one JVM argument per line
'   Read as Unicode so non-ASCII install paths survive; then run javaw directly,
'   so double-click does not pay for PowerShell startup or the java -version probes.
' Fallback: run "Brisk Launcher Debug.cmd" (which starts launch.ps1) when the
'   fast path is missing/stale, or when an option like --safe was passed.
Option Explicit

Dim fso, sh, dir, hot, text, lines, i, line, exe, cmd, arg, dirLine, ok
Set fso = CreateObject("Scripting.FileSystemObject")
Set sh = CreateObject("WScript.Shell")

dir = fso.GetParentFolderName(WScript.ScriptFullName)
sh.CurrentDirectory = dir

If WScript.Arguments.Count = 0 Then
    hot = dir & "\data\launch.txt"
    If fso.FileExists(hot) Then
        text = ReadUnicode(fso, hot)
        If Len(text) > 0 Then
            If AscW(Left(text, 1)) = 65279 Then text = Mid(text, 2)
            lines = Split(Replace(text, vbCrLf, vbLf), vbLf)
            exe = ""
            dirLine = ""
            cmd = ""
            ok = False
            For i = 0 To UBound(lines)
                line = lines(i)
                If Len(line) > 0 Then
                    If Left(line, 1) = "#" Then
                        If Left(line, 5) = "#dir=" Then dirLine = Mid(line, 6)
                        If Left(line, 4) = "#v=1" Then ok = True
                    ElseIf exe = "" Then
                        exe = Trim(line)
                    Else
                        If Len(Trim(line)) > 0 Then
                            cmd = cmd & " """ & Replace(line, """", """""") & """"
                        End If
                    End If
                End If
            Next
            ' 只有「格式對、安裝目錄沒搬家、javaw 還在」才走熱路徑
            If ok And (dirLine = "") Then ok = False
            If ok Then
                If StrComp(dirLine, dir, vbTextCompare) <> 0 Then ok = False
            End If
            If ok Then
                If Len(exe) = 0 Then ok = False
            End If
            If ok Then
                If Not fso.FileExists(exe) Then ok = False
            End If
            If ok Then
                sh.Run """" & exe & """" & cmd, 0, False
                WScript.Quit 0
            End If
        End If
    End If
End If

' Fallback: PowerShell bootstrap (first run, Java missing, moved folder, options).
sh.Run "cmd /c """"" & dir & "\Brisk Launcher Debug.cmd""""", 0, False
WScript.Quit 0

' Read a text file; -1 = TristateTrue (auto-detect Unicode BOM).
Function ReadUnicode(fsoRef, path)
    Dim stream
    ReadUnicode = ""
    On Error Resume Next
    Set stream = fsoRef.OpenTextFile(path, 1, False, -1)
    If Err.Number = 0 Then
        ReadUnicode = stream.ReadAll
        stream.Close
    End If
    On Error GoTo 0
End Function
