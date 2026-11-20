package com.pingip.app;

interface IShellService {
    void destroy() = 16777114;
    String exec(String command) = 1;
}
