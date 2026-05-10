#include "Cafe/Android/AndroidPerformanceHints.h"

#include "Cemu/Logging/CemuLogging.h"

#include <android/api-level.h>

#include <algorithm>
#include <dlfcn.h>
#include <mutex>
#include <vector>

namespace
{
struct APerformanceHintManager;
struct APerformanceHintSession;

using GetManagerFn = APerformanceHintManager* (*)();
using CreateSessionFn = APerformanceHintSession* (*)(APerformanceHintManager*, const int32_t*, size_t, int64_t);
using UpdateTargetWorkDurationFn = int (*)(APerformanceHintSession*, int64_t);
using ReportActualWorkDurationFn = int (*)(APerformanceHintSession*, int64_t);
using CloseSessionFn = void (*)(APerformanceHintSession*);

constexpr int64_t kTargetFrameDurationNs = 16'666'667;
constexpr int64_t kMinReportedDurationNs = 1'000'000;
constexpr int64_t kMaxReportedDurationNs = 100'000'000;

std::mutex s_hintMutex;
void* s_libAndroid{};
APerformanceHintManager* s_manager{};
APerformanceHintSession* s_session{};
GetManagerFn s_getManager{};
CreateSessionFn s_createSession{};
UpdateTargetWorkDurationFn s_updateTargetWorkDuration{};
ReportActualWorkDurationFn s_reportActualWorkDuration{};
CloseSessionFn s_closeSession{};
bool s_loaded{};
bool s_unavailableLogged{};
bool s_sessionLogged{};
std::chrono::steady_clock::time_point s_lastFrameBoundary{};

template <typename T>
T loadFunction(const char* symbolName)
{
	return reinterpret_cast<T>(dlsym(s_libAndroid, symbolName));
}

bool loadPerformanceHintApi()
{
	if (s_loaded)
		return s_getManager && s_createSession && s_updateTargetWorkDuration && s_reportActualWorkDuration && s_closeSession;

	s_loaded = true;

	if (android_get_device_api_level() < 33)
		return false;

	s_libAndroid = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
	if (!s_libAndroid)
		return false;

	s_getManager = loadFunction<GetManagerFn>("APerformanceHint_getManager");
	s_createSession = loadFunction<CreateSessionFn>("APerformanceHint_createSession");
	s_updateTargetWorkDuration = loadFunction<UpdateTargetWorkDurationFn>("APerformanceHint_updateTargetWorkDuration");
	s_reportActualWorkDuration = loadFunction<ReportActualWorkDurationFn>("APerformanceHint_reportActualWorkDuration");
	s_closeSession = loadFunction<CloseSessionFn>("APerformanceHint_closeSession");

	if (!(s_getManager && s_createSession && s_updateTargetWorkDuration && s_reportActualWorkDuration && s_closeSession))
		return false;

	s_manager = s_getManager();
	return s_manager != nullptr;
}
}

namespace AndroidPerformanceHints
{
void UpdateSchedulerThreads(const std::vector<pid_t>& threadIds, size_t expectedThreadCount)
{
	std::lock_guard lock(s_hintMutex);

	if (s_session || expectedThreadCount == 0 || threadIds.size() < expectedThreadCount)
		return;

	if (!loadPerformanceHintApi())
	{
		if (!s_unavailableLogged)
		{
			s_unavailableLogged = true;
			cemuLog_log(LogType::Force, "Android ADPF performance hints unavailable");
		}
		return;
	}

	std::vector<int32_t> sessionThreadIds;
	sessionThreadIds.reserve(expectedThreadCount);
	for (size_t i = 0; i < expectedThreadCount; ++i)
		sessionThreadIds.emplace_back(static_cast<int32_t>(threadIds[i]));

	s_session = s_createSession(
		s_manager,
		sessionThreadIds.data(),
		sessionThreadIds.size(),
		kTargetFrameDurationNs);

	if (!s_session)
	{
		if (!s_unavailableLogged)
		{
			s_unavailableLogged = true;
			cemuLog_log(LogType::Force, "Android ADPF performance hint session creation failed");
		}
		return;
	}

	s_updateTargetWorkDuration(s_session, kTargetFrameDurationNs);
	s_lastFrameBoundary = std::chrono::steady_clock::now();

	if (!s_sessionLogged)
	{
		s_sessionLogged = true;
		cemuLog_log(LogType::Force, "Android ADPF performance hints enabled for {} CPU scheduler thread(s)", sessionThreadIds.size());
	}
}

void ReportFrameBoundary(std::chrono::steady_clock::time_point now)
{
	std::lock_guard lock(s_hintMutex);

	if (!s_session)
		return;

	if (s_lastFrameBoundary == std::chrono::steady_clock::time_point{})
	{
		s_lastFrameBoundary = now;
		return;
	}

	const auto duration = static_cast<int64_t>(
		std::chrono::duration_cast<std::chrono::nanoseconds>(now - s_lastFrameBoundary).count()
	);
	s_lastFrameBoundary = now;
	const int64_t clampedDuration = std::clamp(duration, kMinReportedDurationNs, kMaxReportedDurationNs);
	s_reportActualWorkDuration(s_session, clampedDuration);
}

void CloseSchedulerSession()
{
	std::lock_guard lock(s_hintMutex);

	if (s_session)
	{
		s_closeSession(s_session);
		s_session = nullptr;
	}
	s_lastFrameBoundary = {};
}
}
