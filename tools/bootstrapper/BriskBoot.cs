// Brisk Launcher 原生启动器（单 exe，做法接近 HMCL）
//
// 形态：
//   BriskLauncher.exe      内嵌全部 lib/*.jar（双击就跑，不需要用户解压一堆文件）
//   runtime\lib\*.jar      运行时释放到这里（JVM 只认磁盘上的 jar，这是 Java 的硬限制）
//   data\                  启动器数据（账号/设置/缓存/主题/logs）
//   minecraft\             内置游戏目录（versions\<id>\ 版本隔离）
//
// runtime 是**一个固定目录、反复复用**：每次启动只补写缺失或大小不对的 jar，
// 并删掉不再内嵌的旧 jar —— 升级后不会残留、也不会每版多出 30MB。
// 旧版曾释放到 %LOCALAPPDATA%\BriskLauncher\runtime\<id>，启动时顺手清掉。
//
// 找不到 Java 21 时：自动下到 data\jre（用内嵌的 install-java.ps1，带 SHA-256 校验）。
//
// 编译：build.gradle 的 bootExe 任务（csc.exe）。

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

internal static class BriskBoot
{
    private const string MainModule = "com.brisklauncher.mcl/com.brisklauncher.mcl.MclApplication";

    /// <summary>与 build.gradle 的 applicationDefaultJvmArgs 保持一致。</summary>
    private static readonly string[] JvmOptions =
    {
        "-Xms16m", "-Xmx192m", "-XX:MaxMetaspaceSize=128m",
        "-XX:ReservedCodeCacheSize=32m", "-XX:CompressedClassSpaceSize=24m",
        "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:CICompilerCount=1",
        "-Xss512k", "-XX:-UsePerfData",
        "-Dhttps.protocols=TLSv1.2",
        "-Djdk.httpclient.allowRestrictedHeaders=connection",
        "-Djava.net.preferIPv4Stack=true",
    };

    /// <summary>引导器自己的日志：卡在「gif 之后、主界面之前」时，这里能看到卡在哪一步。</summary>
    private static string bootLogPath;

    private static void BootLog(string message)
    {
        try
        {
            if (bootLogPath == null)
            {
                return;
            }
            File.AppendAllText(bootLogPath,
                DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + "  " + message + Environment.NewLine);
        }
        catch (Exception)
        {
            // 日志失败不能影响启动
        }
    }

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            Launch(args);
            return 0;
        }
        catch (Exception error)
        {
            MessageBox.Show("Brisk Launcher 启动失败：\n\n" + error.Message, "Brisk Launcher",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private static void Launch(string[] args)
    {
        string exeDir = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
        try
        {
            string logDir = Path.Combine(exeDir, "data", "logs", "boot");
            Directory.CreateDirectory(logDir);
            bootLogPath = Path.Combine(logDir, "boot-" + DateTime.Now.ToString("yyyy-MM-dd-HHmmss") + ".log");
            BootLog("BriskBoot 启动（exe 目录 " + exeDir + "）");
        }
        catch (Exception)
        {
            bootLogPath = null;
        }
        string dataDir = Path.Combine(exeDir, "data");
        string runtimeDir = Path.Combine(exeDir, "runtime");
        string libDir = Path.Combine(runtimeDir, "lib");
        Directory.CreateDirectory(libDir);
        Directory.CreateDirectory(dataDir);
        // 临时目录也放在 exe 旁边：%TEMP% 不可写时（受限目录 / 安全软件）javax.imageio 会直接失败
        string tempDir = Path.Combine(dataDir, "tmp");
        Directory.CreateDirectory(tempDir);
        Directory.CreateDirectory(Path.Combine(exeDir, "minecraft", "versions"));

        BootLog("开始同步 runtime/lib");
        SyncLibraries(libDir);
        BootLog("runtime/lib 就绪");
        string splash = ExtractIfChanged("splash.gif", runtimeDir);
        ExtractIfChanged("install-java.ps1", runtimeDir);
        WriteBundleStamp(runtimeDir);
        CleanupLegacyCache();

        string java = FindJava(exeDir);
        if (java == null)
        {
            java = InstallJava(exeDir, runtimeDir);
        }
        if (java == null)
        {
            MessageBox.Show(
                "没有找到 Java 21，自动下载也没有成功。\n\n"
                + "可以手动装一个 64 位 Java 21，或者双击 runtime 目录里的 install-java.ps1。\n"
                + "装好后重新双击 BriskLauncher.exe。",
                "Brisk Launcher", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            Environment.Exit(2);
        }

        StringBuilder command = new StringBuilder();
        foreach (string option in JvmOptions)
        {
            command.Append(option).Append(' ');
        }
        command.Append("-Dmcl.home=").Append(Quote(dataDir)).Append(' ');
        command.Append("-Dmcl.dataDir=").Append(Quote(dataDir)).Append(' ');
        command.Append("-Dmcl.appDir=").Append(Quote(exeDir)).Append(' ');
        // 账号清单与凭据命名空间放 %LOCALAPPDATA%\BriskLauncher：
        // exe 随便换路径都能看到同一份账号（凭据管理器本来就是按 Windows 用户存的）
        string accountsDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BriskLauncher");
        Directory.CreateDirectory(accountsDir);
        command.Append("-Dmcl.accountsDir=").Append(Quote(accountsDir)).Append(' ');
        command.Append("--module-path ").Append(Quote(libDir)).Append(' ');
        command.Append("--module ").Append(MainModule).Append(' ');
        foreach (string extra in args)
        {
            command.Append(extra).Append(' ');
        }

        // 启动重试阶梯（第 190 轮）：JVM 偶尔会在写出任何日志前就没了（Windows 事件日志里是
        // Application Hang，退出码 0xCFFFFFFF），此时用户只看到一张卡住的启动图。
        // 做法与 HMCL 一致：换条件重试，最后必须把原因告诉用户，不能静默消失。
        string baseCommand = command.ToString();
        int exitCode = Attempt(java, exeDir, baseCommand, splash, true, null, "第 1 次");
        // 「静默死亡」= 退出码非 0 且这次没有写出任何启动器日志。只有这种才重试/报错：
        // 用户自己关掉启动器、或启动器自己报了错（有日志），都不该再弹一个框。
        bool silent = exitCode != 0 && !lastAttemptProducedLog;
        if (silent)
        {
            BootLog("第 1 次没有产出任何启动器日志（退出码 " + Describe(exitCode)
                    + "），重试：不带 -splash，并打开启动轨迹");
            Thread.Sleep(400);
            exitCode = Attempt(java, exeDir, baseCommand, splash, false,
                    "-Dmcl.startup.trace=1", "第 2 次（无启动图 + 轨迹）");
            silent = exitCode != 0 && !lastAttemptProducedLog;
        }
        if (silent)
        {
            BootLog("第 2 次仍然没有日志，重试：改用软件渲染（-Dprism.order=sw）");
            Thread.Sleep(400);
            exitCode = Attempt(java, exeDir, baseCommand, splash, false,
                    "-Dprism.order=sw -Dmcl.startup.trace=1", "第 3 次（软件渲染）");
            silent = exitCode != 0 && !lastAttemptProducedLog;
        }
        if (silent)
        {
            ShowStartupFailure(exitCode, baseCommand, splash);
        }
        Environment.Exit(exitCode);
    }

    /// <summary>上一次尝试有没有写出新的启动器日志（= JVM 至少跑到 SafeLog）。</summary>
    private static bool lastAttemptProducedLog = true;

    /// <summary>启动阶段最多等这么久还看不到第一行输出，就判定卡死（正常是 1~2 秒）。</summary>
    private const int StartupOutputTimeoutMs = 30000;

    /// <summary>卡死被我们结束时的退出码（124 = 常见的 timeout 约定）。</summary>
    private const int HangExitCode = 124;

    private static string Describe(int code)
    {
        return code + "（0x" + (code & 0xFFFFFFFFL).ToString("X8") + "）";
    }

    /// <summary>记录启动器日志目录当前有哪些文件（用来判断「这次尝试有没有写出新日志」）。</summary>
    private static HashSet<string> SnapshotLauncherLogs(string exeDir)
    {
        HashSet<string> files = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            string dir = Path.Combine(exeDir, "data", "logs");
            if (!Directory.Exists(dir))
            {
                return files;
            }
            foreach (string file in Directory.GetFiles(dir, "*.log"))
            {
                // 游戏会话日志（session-*.log）随时在写，跟启动器有没有起来无关，必须排除，
                // 否则「有游戏正在跑」会被误判成「启动器写出日志了」→ 不重试（第 190 轮）。
                // 启动器自己的日志每次启动都是新的 yyyy-MM-dd-HHmmss.log，比 mtime 可靠。
                if (Path.GetFileName(file).StartsWith("session-", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }
                files.Add(file);
            }
        }
        catch (Exception)
        {
            // 读不到就当成空集合 → 仍然重试，宁可多重试一次
        }
        return files;
    }

    /// <summary>快照之后有没有出现新的启动器日志文件。</summary>
    private static bool HasNewLauncherLog(string exeDir, HashSet<string> before)
    {
        HashSet<string> now = SnapshotLauncherLogs(exeDir);
        now.ExceptWith(before);
        return now.Count > 0;
    }

    /// <summary>
    /// 跑一次 JVM，返回退出码。
    ///
    /// <p>只在**启动阶段**看是否卡死：30 秒内连一行输出都没有（正常 1~2 秒就有），
    /// 就强制结束并回传 {@link HangExitCode}；一旦有输出就继续陪跑到用户关闭启动器。
    /// </summary>
    private static int Attempt(string java, string exeDir, string baseCommand, string splash,
                               bool withSplash, string extraArgs, string label)
    {
        StringBuilder command = new StringBuilder(baseCommand);
        if (withSplash && splash != null)
        {
            command.Append("-splash:").Append(Quote(splash)).Append(' ');
        }
        if (!String.IsNullOrEmpty(extraArgs))
        {
            command.Append(extraArgs).Append(' ');
        }

        ProcessStartInfo start = new ProcessStartInfo
        {
            FileName = java,
            Arguments = command.ToString(),
            WorkingDirectory = exeDir,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        BootLog("[" + label + "] 准备启动 JVM：" + java);
        BootLog("[" + label + "] 命令行：" + java + " " + command);
        HashSet<string> logsBefore = SnapshotLauncherLogs(exeDir);
        using (Process child = new Process { StartInfo = start })
        {
            bool[] sawOutput = new bool[1];
            child.OutputDataReceived += (sender, e) =>
            {
                if (e.Data != null)
                {
                    sawOutput[0] = true;
                    BootLog("JVM  " + e.Data);
                }
            };
            child.ErrorDataReceived += (sender, e) =>
            {
                if (e.Data != null)
                {
                    sawOutput[0] = true;
                    BootLog("JVM! " + e.Data);
                }
            };
            child.Start();
            child.BeginOutputReadLine();
            child.BeginErrorReadLine();
            BootLog("[" + label + "] JVM 已拉起，等待第一行输出（最多 "
                    + (StartupOutputTimeoutMs / 1000) + " 秒）");
            DateTime deadline = DateTime.UtcNow.AddMilliseconds(StartupOutputTimeoutMs);
            while (!sawOutput[0] && !child.HasExited && DateTime.UtcNow < deadline)
            {
                Thread.Sleep(100);
            }
            if (!sawOutput[0] && !child.HasExited)
            {
                BootLog("[" + label + "] 超过 " + (StartupOutputTimeoutMs / 1000)
                        + " 秒没有任何输出，判定卡死（Windows 也会把它标成 Application Hang）");
                try
                {
                    child.Kill();
                }
                catch (Exception)
                {
                    // 已经退出就算了
                }
                try
                {
                    child.WaitForExit(3000);
                }
                catch (Exception)
                {
                }
                lastAttemptProducedLog = HasNewLauncherLog(exeDir, logsBefore);
                return HangExitCode;
            }
            // 继续持有管道直到子进程结束：提前退出会让子进程写 stdout 时报错甚至卡住
            child.WaitForExit();
            int code = child.ExitCode;
            BootLog("[" + label + "] JVM 已退出，退出码 " + Describe(code));
            lastAttemptProducedLog = HasNewLauncherLog(exeDir, logsBefore);
            return code;
        }
    }

    /// <summary>三次都失败时把原因讲清楚（旧版只写进引导器日志，用户双击后什么都看不到）。</summary>
    private static void ShowStartupFailure(int exitCode, string baseCommand, string splash)
    {
        string text = "Brisk Launcher 没能启动（退出码 " + Describe(exitCode) + "）。\n\n"
                + "已经自动重试 3 次（关掉启动图、改用软件渲染），都没有成功。\n\n"
                + "可以把下面这条命令复制到「命令提示符」执行，看 java 自己报什么错：\n\n"
                + "java " + baseCommand + (splash == null ? "" : "-splash:" + Quote(splash) + " ")
                + "\n\n"
                + "引导器日志（每次尝试的结果都在里面）：\n" + bootLogPath;
        MessageBox.Show(text, "Brisk Launcher", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }

    // ---------- 内嵌资源：单目录同步（补写 + 删旧） ----------

    private static void SyncLibraries(string libDir)
    {
        Assembly self = Assembly.GetExecutingAssembly();
        HashSet<string> wanted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string name in self.GetManifestResourceNames())
        {
            if (!name.StartsWith("lib/", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }
            string file = name.Substring(4);
            wanted.Add(file);
            string target = Path.Combine(libDir, file);
            long embeddedLength;
            using (Stream input = self.GetManifestResourceStream(name))
            {
                embeddedLength = input.Length;
                if (File.Exists(target) && new FileInfo(target).Length == embeddedLength)
                {
                    continue;
                }
                using (FileStream output = File.Create(target))
                {
                    input.CopyTo(output);
                }
            }
        }
        // 清掉上一版留下、这一版不再内嵌的 jar（避免占磁盘 / 重复上模块路径）
        foreach (string existing in Directory.GetFiles(libDir))
        {
            string file = Path.GetFileName(existing);
            if (!wanted.Contains(file))
            {
                try
                {
                    File.Delete(existing);
                }
                catch (Exception)
                {
                    // 被占用就算了，下次启动再删
                }
            }
        }
    }

    private static string ExtractIfChanged(string resourceName, string targetDir)
    {
        Assembly self = Assembly.GetExecutingAssembly();
        using (Stream input = self.GetManifestResourceStream(resourceName))
        {
            if (input == null)
            {
                return null;
            }
            string target = Path.Combine(targetDir, resourceName);
            if (!File.Exists(target) || new FileInfo(target).Length != input.Length)
            {
                using (FileStream output = File.Create(target))
                {
                    input.CopyTo(output);
                }
            }
            return target;
        }
    }

    private static void WriteBundleStamp(string runtimeDir)
    {
        try
        {
            File.WriteAllText(Path.Combine(runtimeDir, "bundle.txt"),
                "bundle=" + BundleInfo.Id + Environment.NewLine);
        }
        catch (Exception)
        {
            // 只是诊断信息
        }
    }

    /// <summary>旧版把 jar 释放到 %LOCALAPPDATA%\BriskLauncher\runtime\&lt;id&gt;，现在不用了。</summary>
    private static void CleanupLegacyCache()
    {
        try
        {
            string legacy = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "BriskLauncher", "runtime");
            if (Directory.Exists(legacy))
            {
                Directory.Delete(legacy, true);
            }
        }
        catch (Exception)
        {
            // 删不掉不影响启动
        }
    }

    // ---------- Java ----------

    private static string FindJava(string exeDir)
    {
        List<string> candidates = new List<string>();
        // 启动器自己下过的 JRE 优先（跟着 exe 走，换机器也能用）
        AddJdkFolders(candidates, Path.Combine(exeDir, "data", "jre"), true);
        string home = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(home))
        {
            candidates.Add(Path.Combine(home, "bin", "java.exe"));
        }
        string programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
        AddJdkFolders(candidates, Path.Combine(programFiles, "Java"), false);
        AddJdkFolders(candidates, Path.Combine(programFiles, "Eclipse Adoptium"), false);
        AddJdkFolders(candidates, Path.Combine(programFiles, "Microsoft"), false);
        AddJdkFolders(candidates, Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "BriskLauncher", "jdk"), false);
        candidates.Add("java.exe");

        foreach (string candidate in candidates)
        {
            if (IsJava21OrNewer(candidate))
            {
                return candidate;
            }
        }
        return null;
    }

    private static void AddJdkFolders(List<string> target, string root, bool includeSelf)
    {
        try
        {
            if (!Directory.Exists(root))
            {
                return;
            }
            if (includeSelf)
            {
                target.Add(Path.Combine(root, "bin", "java.exe"));
            }
            foreach (string dir in Directory.GetDirectories(root))
            {
                target.Add(Path.Combine(dir, "bin", "java.exe"));
                foreach (string inner in Directory.GetDirectories(dir))
                {
                    target.Add(Path.Combine(inner, "bin", "java.exe"));
                }
            }
        }
        catch (Exception)
        {
            // 没权限就跳过
        }
    }

    private static bool IsJava21OrNewer(string java)
    {
        try
        {
            ProcessStartInfo info = new ProcessStartInfo
            {
                FileName = java,
                Arguments = "-version",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                RedirectStandardOutput = true,
            };
            using (Process probe = Process.Start(info))
            {
                string text = probe.StandardError.ReadToEnd() + probe.StandardOutput.ReadToEnd();
                probe.WaitForExit(5000);
                Match match = Regex.Match(text, "version \"(\\d+)");
                int major;
                if (!match.Success || !int.TryParse(match.Groups[1].Value, out major))
                {
                    return false;
                }
                return major >= 21;
            }
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>缺 Java 时：用内嵌的 install-java.ps1 下到 data\jre（脚本自带 SHA-256 校验）。</summary>
    private static string InstallJava(string exeDir, string runtimeDir)
    {
        string script = Path.Combine(runtimeDir, "install-java.ps1");
        if (!File.Exists(script))
        {
            return null;
        }
        DialogResult answer = MessageBox.Show(
            "第一次启动需要 Java 21，但没有找到。\n\n"
            + "现在自动下载到 data\\jre（约 180 MB，只需一次）。\n"
            + "点确定开始，下载过程中会开一个命令行窗口显示进度。",
            "Brisk Launcher", MessageBoxButtons.OKCancel, MessageBoxIcon.Information);
        if (answer != DialogResult.OK)
        {
            return null;
        }
        string target = Path.Combine(exeDir, "data", "jre");
        try
        {
            ProcessStartInfo info = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = "-NoProfile -ExecutionPolicy Bypass -File " + Quote(script)
                        + " -Target " + Quote(target),
                UseShellExecute = false,
                CreateNoWindow = false,
                WorkingDirectory = exeDir,
            };
            using (Process install = Process.Start(info))
            {
                install.WaitForExit();
            }
        }
        catch (Exception)
        {
            return null;
        }
        string java = Path.Combine(target, "bin", "java.exe");
        return IsJava21OrNewer(java) ? java : null;
    }

    private static string Quote(string value)
    {
        return "\"" + value + "\"";
    }
}
