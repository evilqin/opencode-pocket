// Shizuku 用户服务：这段 AIDL 会被编译成 Binder 接口。
//
// Shizuku 会把实现类加载到自己的 shell/root 进程里（实现类必须继承 Stub，
// 不能是 android.app.Service），于是 App 就能以 shell 身份在手机本体上执行命令。
package dev.pocket.opencode;

interface IDeviceShellService {
    /** Shizuku 保留的销毁事务号：必须实现并结束进程。 */
    void destroy() = 16777114;

    /** 服务进程的 uid：2000 = shell(adb)，0 = root。 */
    int getUid() = 1;

    /** 执行一条命令，返回 JSON：{exitCode, stdout, stderr, timedOut}。 */
    String exec(String command, int timeoutMs) = 2;
}
