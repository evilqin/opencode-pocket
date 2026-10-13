// Shizuku 用户服务：这段 AIDL 会被编译成 Binder 接口，
// 由 Shizuku 在 shell/root 进程里加载我们的实现（DeviceShellUserService），
// 于是 App 就能以 shell 身份在手机本体上执行命令。
package dev.pocket.opencode;

interface IDeviceShellService {
    /** 服务进程的 uid：2000 = shell(adb)，0 = root。 */
    int getUid();

    /** 执行一条命令，返回 JSON：{exitCode, stdout, stderr, timedOut}。 */
    String exec(String command, int timeoutMs);
}
