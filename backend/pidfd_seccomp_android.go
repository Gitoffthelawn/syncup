//go:build android

// Workaround for a startup crash on Android 10.

package gobridge

/*
#include <errno.h>
#include <signal.h>
#include <string.h>
#include <ucontext.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

static void syncup_sigsys_handler(int sig, siginfo_t *info, void *uctx) {
	if (info->si_code == SYS_SECCOMP) {
		ucontext_t *c = (ucontext_t *)uctx;
#if defined(__aarch64__)
		c->uc_mcontext.regs[0] = -ENOSYS;
#elif defined(__x86_64__)
		c->uc_mcontext.gregs[REG_RAX] = -ENOSYS;
#elif defined(__arm__)
		c->uc_mcontext.arm_r0 = (unsigned long)(-ENOSYS);
#elif defined(__i386__)
		c->uc_mcontext.gregs[REG_EAX] = -ENOSYS;
#endif
		return;
	}
	// restore the default disposition and re-raise
	signal(sig, SIG_DFL);
	raise(sig);
}

__attribute__((constructor)) static void syncup_install_sigsys_handler(void) {
	struct sigaction sa;
	memset(&sa, 0, sizeof(sa));
	sa.sa_sigaction = syncup_sigsys_handler;
	sa.sa_flags = SA_SIGINFO | SA_RESTART;
	sigaction(SIGSYS, &sa, NULL);
}
*/
import "C"
