package com.storageplanner.v2test;

import java.util.Arrays;
import java.util.List;

/** Runs every v2 test suite in a fixed order (no reflection). Args: optional suite names to run only those. */
public final class AllV2Tests {
    interface Suite { int run(String[] args) throws Exception; }

    public static void main(String[] args) {
        Object[][] suites = {
            {"ForbiddenApiTest", (Suite) ForbiddenApiTest::run},
            {"PathNormalizerTest", (Suite) PathNormalizerTest::run},
            {"FileIdsTest", (Suite) FileIdsTest::run},
            {"JsonTest", (Suite) JsonTest::run},
            {"StoreContractTest", (Suite) StoreContractTest::run},
            {"StorePersistenceTest", (Suite) StorePersistenceTest::run},
            {"FrameTest", (Suite) FrameTest::run},
            {"DiskStoreTest", (Suite) DiskStoreTest::run},
            {"StoreOracleTest", (Suite) StoreOracleTest::run},
            {"DiskKillTest", (Suite) DiskKillTest::run},
            {"ResetSafetyTest", (Suite) ResetSafetyTest::run},
            {"DiskCorruptionTest", (Suite) DiskCorruptionTest::run},
            {"DiskSpaceTest", (Suite) DiskSpaceTest::run},
            {"StreamingTest", (Suite) StreamingTest::run},
            {"CapabilityRegistryTest",(Suite) CapabilityRegistryTest::run},
            {"VolumeRegistryTest", (Suite) VolumeRegistryTest::run},
            {"WalkerTest", (Suite) WalkerTest::run},
            {"DiscoveryResumeTest", (Suite) DiscoveryResumeTest::run},
            {"IncrementalScanTest", (Suite) IncrementalScanTest::run},
            {"PermissionChangeTest", (Suite) PermissionChangeTest::run},
            {"CoverageTest", (Suite) CoverageTest::run},
            {"V2CliTest", (Suite) V2CliTest::run},
            {"TypeDetectorTest", (Suite) TypeDetectorTest::run},
            {"HashStageTest", (Suite) HashStageTest::run},
            {"ImageTest", (Suite) ImageTest::run},
            {"ZipApkTest", (Suite) ZipApkTest::run},
            {"PdfProbeTest", (Suite) PdfProbeTest::run},
            {"Mp4ProbeTest", (Suite) Mp4ProbeTest::run},
            {"IncrementalExtractionTest", (Suite) IncrementalExtractionTest::run},
            {"ExactGrouperTest", (Suite) ExactGrouperTest::run},
            {"PerceptualTest", (Suite) PerceptualTest::run},
            {"PerceptualBudgetTest", (Suite) PerceptualBudgetTest::run},
            {"ArchiveMatcherTest", (Suite) ArchiveMatcherTest::run},
            {"ScenarioFixtureTest", (Suite) ScenarioFixtureTest::run},
            {"HardRulesTest", (Suite) HardRulesTest::run},
            {"EstimatorsTest", (Suite) EstimatorsTest::run},
            {"RiskPropertyTest", (Suite) RiskPropertyTest::run},
            {"UnitBuilder2Test", (Suite) UnitBuilder2Test::run},
            {"Planner2Test", (Suite) Planner2Test::run},
            {"PlanPropertyTest", (Suite) PlanPropertyTest::run},
            {"YourCallEligibilityTest", (Suite) YourCallEligibilityTest::run},
            {"V2AdapterTest", (Suite) V2AdapterTest::run},
        };
        List<String> only = Arrays.asList(args);
        int failures = 0, ran = 0;
        long t0 = System.nanoTime();
        for (Object[] s : suites) {
            String name = (String) s[0];
            if (!only.isEmpty() && !only.contains(name)) continue;
            ran++;
            try {
                failures += ((Suite) s[1]).run(args);
            } catch (Throwable e) {   // jvmtest: a crashing suite (incl. an escaped SimulatedKill) is a failure
                failures++;
                System.out.println(name + ": CRASHED");
                e.printStackTrace(System.out);
            }
        }
        System.out.println("v2 tests: " + ran + " suites, " + failures + " failures (" + (System.nanoTime() - t0) / 1000000L + " ms)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
