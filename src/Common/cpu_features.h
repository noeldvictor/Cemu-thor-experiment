#ifdef __GNUC__
#define ATTRIBUTE_AVX2 __attribute__((target("avx2")))
#define ATTRIBUTE_SSE41 __attribute__((target("sse4.1")))
#define ATTRIBUTE_AESNI __attribute__((target("aes")))
#else
#define ATTRIBUTE_AVX2
#define ATTRIBUTE_SSE41
#define ATTRIBUTE_AESNI
#endif

#include <string>

class CPUFeaturesImpl
{
public:
	CPUFeaturesImpl();

	std::string GetCPUName(); // empty if not available
	std::string GetCommaSeparatedExtensionList();

	struct
	{
		bool ssse3{ false };
		bool sse4_1{ false };
		bool avx{ false };
		bool avx2{ false };
		bool lzcnt{ false };
		bool movbe{ false };
		bool bmi2{ false };
		bool aesni{ false };
		bool invariant_tsc{ false };
	}x86;

	// AArch64 optional features. NEON/ASIMD and FP are mandatory in ARMv8-A and
	// therefore not tracked here. Everything below is optional and must be
	// checked before using an intrinsic or inline asm that relies on it.
	struct
	{
		bool atomics{ false };  // FEAT_LSE - CAS/LDADD/SWP instead of LL/SC retry loops
		bool asimdhp{ false };  // FEAT_FP16 - half precision arithmetic
		bool asimddp{ false };  // FEAT_DotProd - UDOT/SDOT
		bool i8mm{ false };     // FEAT_I8MM - integer matrix multiply
		bool sha3{ false };     // FEAT_SHA3 - also provides EOR3/BCAX/RAX1/XAR
		bool sve{ false };      // FEAT_SVE - absent on Snapdragon 8 Gen 2
	}arm;
private:
	std::string m_cpuBrandName;
};

extern CPUFeaturesImpl g_CPUFeatures;
