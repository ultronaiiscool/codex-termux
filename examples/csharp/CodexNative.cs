using System;
using System.Runtime.InteropServices;
using System.Text;

internal static class CodexNative
{
    private const string LibraryName = "codex_app_server";

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern int codex_app_server_start(
        string bindAddress,
        string codexHome,
        string tokenSha256);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern int codex_app_server_stop();

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern int codex_app_server_is_running();

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    private static extern IntPtr codex_app_server_version();

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    internal static extern void codex_app_server_clear_error();

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl)]
    private static extern UIntPtr codex_app_server_last_error(
        IntPtr buffer,
        UIntPtr bufferLength);

    internal static string LastError()
    {
        UIntPtr requiredValue = codex_app_server_last_error(IntPtr.Zero, UIntPtr.Zero);
        ulong required = requiredValue.ToUInt64();
        if (required <= 1)
            return string.Empty;

        IntPtr buffer = Marshal.AllocHGlobal(checked((int)required));
        try
        {
            codex_app_server_last_error(buffer, (UIntPtr)required);
            return Marshal.PtrToStringUTF8(buffer) ?? string.Empty;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    internal static string Version()
    {
        return Marshal.PtrToStringUTF8(codex_app_server_version()) ?? string.Empty;
    }
}
