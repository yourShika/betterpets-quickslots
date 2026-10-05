param(
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [Parameter(Mandatory = $true)][string]$CommandFile,
    [int]$TimeoutSeconds = 600
)
# Runs a command on a Windows desktop of its own and waits for it to finish.
#
# The command's windows exist and render there, but nothing appears on the desktop you are working on and
# nothing takes the keyboard focus away from it. run-preview.sh uses this (HIDDEN=1) to start the game for
# its test run without a window popping up in front of whatever else is going on.
#
# The command line is read from a file rather than passed as an argument: it is long and full of quotes.

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class HiddenDesktop
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct STARTUPINFO
    {
        public int cb; public string lpReserved; public string lpDesktop; public string lpTitle;
        public int dwX; public int dwY; public int dwXSize; public int dwYSize;
        public int dwXCountChars; public int dwYCountChars; public int dwFillAttribute; public int dwFlags;
        public short wShowWindow; public short cbReserved2; public IntPtr lpReserved2;
        public IntPtr hStdInput; public IntPtr hStdOutput; public IntPtr hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROCESS_INFORMATION { public IntPtr hProcess; public IntPtr hThread; public int dwProcessId; public int dwThreadId; }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr CreateDesktop(string lpszDesktop, IntPtr lpszDevice, IntPtr pDevmode, int dwFlags, uint dwDesiredAccess, IntPtr lpsa);

    [DllImport("user32.dll", SetLastError = true)]
    static extern bool CloseDesktop(IntPtr hDesktop);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CreateProcess(string lpApplicationName, StringBuilder lpCommandLine, IntPtr lpProcessAttributes,
        IntPtr lpThreadAttributes, bool bInheritHandles, uint dwCreationFlags, IntPtr lpEnvironment, string lpCurrentDirectory,
        ref STARTUPINFO lpStartupInfo, out PROCESS_INFORMATION lpProcessInformation);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern uint WaitForSingleObject(IntPtr hHandle, uint dwMilliseconds);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool GetExitCodeProcess(IntPtr hProcess, out uint lpExitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool TerminateProcess(IntPtr hProcess, uint uExitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool CloseHandle(IntPtr hObject);

    public static int Run(string desktopName, string workDir, string commandLine, int timeoutSeconds)
    {
        const uint GENERIC_ALL = 0x10000000;
        const uint CREATE_NO_WINDOW = 0x08000000;
        IntPtr desktop = CreateDesktop(desktopName, IntPtr.Zero, IntPtr.Zero, 0, GENERIC_ALL, IntPtr.Zero);
        if (desktop == IntPtr.Zero)
        {
            Console.Error.WriteLine("CreateDesktop failed: " + Marshal.GetLastWin32Error());
            return 1001;
        }
        try
        {
            STARTUPINFO startup = new STARTUPINFO();
            startup.cb = Marshal.SizeOf(typeof(STARTUPINFO));
            startup.lpDesktop = "WinSta0\\" + desktopName;
            PROCESS_INFORMATION process;
            if (!CreateProcess(null, new StringBuilder(commandLine), IntPtr.Zero, IntPtr.Zero, false, CREATE_NO_WINDOW, IntPtr.Zero, workDir, ref startup, out process))
            {
                Console.Error.WriteLine("CreateProcess failed: " + Marshal.GetLastWin32Error());
                return 1002;
            }
            uint code;
            if (WaitForSingleObject(process.hProcess, (uint)timeoutSeconds * 1000) != 0)
            {
                // Nobody could close a window nobody can see: take the whole process tree down.
                Console.Error.WriteLine("Still running after " + timeoutSeconds + " seconds - stopping it");
                System.Diagnostics.Process killer = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(
                    "taskkill.exe", "/PID " + process.dwProcessId + " /T /F") { UseShellExecute = false, CreateNoWindow = true });
                killer.WaitForExit();
                TerminateProcess(process.hProcess, 1003);
                code = 1003;
            }
            else
            {
                GetExitCodeProcess(process.hProcess, out code);
            }
            CloseHandle(process.hThread);
            CloseHandle(process.hProcess);
            return (int)code;
        }
        finally
        {
            CloseDesktop(desktop);
        }
    }
}
"@

$commandLine = (Get-Content -Raw -Encoding UTF8 $CommandFile).Trim()
exit [HiddenDesktop]::Run("QuickslotsPreview" + $PID, $WorkDir, $commandLine, $TimeoutSeconds)
