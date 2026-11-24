package com.pingip.app;

interface IShellService {
    void destroy() = 16777114;
    void exit() = 1;
    String exec(String command) = 2;
}
