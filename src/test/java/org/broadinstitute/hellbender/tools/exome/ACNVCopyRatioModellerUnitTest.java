package org.broadinstitute.hellbender.tools.exome;

import com.google.common.primitives.Doubles;
import htsjdk.samtools.util.Log;
import org.apache.commons.io.FileUtils;
import org.apache.commons.math3.stat.descriptive.moment.Mean;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.spark.api.java.JavaSparkContext;
import org.broadinstitute.hellbender.engine.spark.SparkContextFactory;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.LoggingUtils;
import org.broadinstitute.hellbender.utils.mcmc.PosteriorSummary;
import org.broadinstitute.hellbender.utils.test.BaseTest;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Unit tests for {@link ACNVCopyRatioModeller}.
 * <p>
 *     Test data consisting of coverage and segment files for 100 segments with an average of 25 targets each
 *     was generated by the ipython notebook scripts/generate-test-data-for-copy-ratio-modeller-test.ipynb.
 *     The global parameters determining the variance and the outlier probability were set to 1.5 and 0.025,
 *     respectively.  The segment means were drawn from Uniform(-5, 5), while outlier points were drawn from
 *     Uniform(-10, 10).
 * </p>
 *
 * @author Samuel Lee &lt;slee@broadinstitute.org&gt;
 */
public final class ACNVCopyRatioModellerUnitTest extends BaseTest {
    private static final String TEST_SUB_DIR = publicTestDir + "org/broadinstitute/hellbender/tools/exome/";

    private static final String SAMPLE_NAME = "test";
    private static final File COVERAGES_FILE = new File(TEST_SUB_DIR
            + "coverages-for-copy-ratio-modeller.tsv");
    private static final File SEGMENT_FILE =
            new File(TEST_SUB_DIR + "segments-for-copy-ratio-modeller.seg");
    private static final File MEANS_TRUTH_FILE = new File(TEST_SUB_DIR
            + "segment-means-truth-for-copy-ratio-modeller.txt");
    private static final File OUTLIER_INDICATORS_TRUTH_FILE = new File(TEST_SUB_DIR
            + "outlier-indicators-truth-for-copy-ratio-modeller.txt");

    private static final double CREDIBLE_INTERVAL_ALPHA = 0.32;

    private static final double VARIANCE_TRUTH = 1.;
    private static final double OUTLIER_PROBABILITY_TRUTH = 0.025;

    //truths for the posterior standard deviations are based on the standard deviations of the appropriate analytic
    //posteriors, scaled appropriately for the total number of coverages or the average number of coverages per segment
    private static final double VARIANCE_POSTERIOR_STANDARD_DEVIATION_TRUTH = 0.03; //inverse chi-squared
    private static final double OUTLIER_PROBABILITY_POSTERIOR_STANDARD_DEVIATION_TRUTH = 0.0032;  //Beta
    private static final double MEAN_POSTERIOR_STANDARD_DEVIATION_MEAN_TRUTH = 0.2; //Gaussian

    private static final int NUM_SAMPLES = 500;
    private static final int NUM_BURN_IN = 250;

    //Calculates relative error between x and xTrue, with respect to xTrue; used for checking statistics of
    //posterior samples below.
    private static double relativeError(final double x, final double xTrue) {
        return Math.abs((x - xTrue) / xTrue);
    }

    //Loads test data from files
    private static <T> List<T> loadList(final File file, final Function<String, T> parse) {
        try {
            return FileUtils.readLines(file).stream().map(parse).collect(Collectors.toList());
        } catch (final IOException e) {
            throw new UserException.CouldNotReadInputFile(file, e);
        }
    }

    /**
     * Tests Bayesian inference of the copy-ratio model via MCMC.
     * <p>
     *     Recovery of input values for the variance and outlier-probability global parameters is checked.
     *     In particular, the true input value of the variance must fall within 3 standard deviations of the posterior
     *     mean and the standard deviation of the posterior must agree with the analytic value to within a relative
     *     error of 20% for 250 samples (after 250 burn-in samples have been discarded).  Similar criteria are applied
     *     to the recovery of the true input value for the outlier probability, but only a relative error of 25% is
     *     required for the posterior standard deviation.
     * </p>
     * <p>
     *     Furthermore, the number of truth values for the segment-level means falling outside confidence intervals of
     *     1-sigma, 2-sigma, and 3-sigma given by the posteriors in each segment should be roughly consistent with
     *     a normal distribution (i.e., ~32, ~5, and ~0, respectively; we allow for errors of 15, 6, and 2).
     *     The mean of the standard deviations of the posteriors for the segment-level means should also be
     *     recovered to within a relative error of 10%.
     * </p>
     * <p>
     *     Finally, the recovered values for the latent outlier-indicator parameters should agree with those used to
     *     generate the data.  For each indicator, the recovered value (i.e., outlier or non-outlier) is taken to be
     *     that given by the majority of posterior samples.  We require that at least 95% of the ~2500 indicators
     *     are recovered correctly.
     * </p>
     * <p>
     *     With these specifications, this unit test is not overly brittle (i.e., it should pass for a large majority
     *     of randomly generated data sets), but it is still brittle enough to check for correctness of the sampling
     *     (for example, specifying a sufficiently incorrect likelihood will cause the test to fail).
     * </p>
     */
    @Test
    public void testRunMCMCOnCopyRatioSegmentedModel() {
        final JavaSparkContext ctx = SparkContextFactory.getTestSparkContext();
        LoggingUtils.setLoggingLevel(Log.LogLevel.INFO);

        //load data (coverages and number of targets in each segment)
        final List<TargetCoverage> targetCoverages = TargetCoverageUtils.readTargetsWithCoverage(COVERAGES_FILE);
        final Genome genome = new Genome(targetCoverages, Collections.emptyList(), SAMPLE_NAME); //Genome with no SNPs
        final SegmentedModel segmentedModel = new SegmentedModel(SEGMENT_FILE, genome);

        //run MCMC
        final ACNVCopyRatioModeller modeller = new ACNVCopyRatioModeller(segmentedModel);
        modeller.fitMCMC(NUM_SAMPLES, NUM_BURN_IN);

        //check statistics of global-parameter posterior samples (i.e., posterior mean and standard deviation)
        final double[] varianceSamples = Doubles.toArray(modeller.getVarianceSamples());
        final double variancePosteriorMean = new Mean().evaluate(varianceSamples);
        final double variancePosteriorStandardDeviation =
                new StandardDeviation().evaluate(varianceSamples);
        Assert.assertEquals(Math.abs(variancePosteriorMean - VARIANCE_TRUTH),
                0., 3 * VARIANCE_POSTERIOR_STANDARD_DEVIATION_TRUTH);
        Assert.assertEquals(
                relativeError(variancePosteriorStandardDeviation, VARIANCE_POSTERIOR_STANDARD_DEVIATION_TRUTH),
                0., 0.2);

        final double[] outlierProbabilitySamples = Doubles.toArray(modeller.getOutlierProbabilitySamples());
        final double outlierProbabilityPosteriorMean = new Mean().evaluate(outlierProbabilitySamples);
        final double outlierProbabilityPosteriorStandardDeviation =
                new StandardDeviation().evaluate(outlierProbabilitySamples);
        Assert.assertEquals(Math.abs(outlierProbabilityPosteriorMean - OUTLIER_PROBABILITY_TRUTH),
                0., 3 * OUTLIER_PROBABILITY_POSTERIOR_STANDARD_DEVIATION_TRUTH);
        Assert.assertEquals(
                relativeError(outlierProbabilityPosteriorStandardDeviation,
                        OUTLIER_PROBABILITY_POSTERIOR_STANDARD_DEVIATION_TRUTH),
                0., 0.25);

        //check statistics of segment-mean posterior samples (i.e., posterior means and standard deviations)
        final List<Double> meansTruth = loadList(MEANS_TRUTH_FILE, Double::parseDouble);
        int numMeansOutsideOneSigma = 0;
        int numMeansOutsideTwoSigma = 0;
        int numMeansOutsideThreeSigma = 0;
        final int numSegments = meansTruth.size();
        //segment-mean posteriors are expected to be Gaussian, so PosteriorSummary for CREDIBLE_INTERVAL_ALPHA=0.32 is
        //(posterior mean, posterior mean - posterior standard devation, posterior mean + posterior standard deviation)
        final List<PosteriorSummary> meanPosteriorSummaries =
                modeller.getSegmentMeansPosteriorSummaries(CREDIBLE_INTERVAL_ALPHA, ctx);
        final double[] meanPosteriorStandardDeviations = new double[numSegments];
        for (int segment = 0; segment < numSegments; segment++) {
            final double meanPosteriorMean = meanPosteriorSummaries.get(segment).center();
            final double meanPosteriorStandardDeviation =
                    (meanPosteriorSummaries.get(segment).upper() - meanPosteriorSummaries.get(segment).lower()) / 2.;
            meanPosteriorStandardDeviations[segment] = meanPosteriorStandardDeviation;
            final double absoluteDifferenceFromTruth = Math.abs(meanPosteriorMean - meansTruth.get(segment));
            if (absoluteDifferenceFromTruth > meanPosteriorStandardDeviation) {
                numMeansOutsideOneSigma++;
            }
            if (absoluteDifferenceFromTruth > 2 * meanPosteriorStandardDeviation) {
                numMeansOutsideTwoSigma++;
            }
            if (absoluteDifferenceFromTruth > 3 * meanPosteriorStandardDeviation) {
                numMeansOutsideThreeSigma++;
            }
        }
        final double meanPosteriorStandardDeviationsMean =
                new Mean().evaluate(meanPosteriorStandardDeviations);
        Assert.assertEquals(numMeansOutsideOneSigma, 100 - 68, 15);
        Assert.assertEquals(numMeansOutsideTwoSigma, 100 - 95, 6);
        Assert.assertTrue(numMeansOutsideThreeSigma <= 2);
        Assert.assertEquals(
                relativeError(meanPosteriorStandardDeviationsMean, MEAN_POSTERIOR_STANDARD_DEVIATION_MEAN_TRUTH),
                0., 0.1);

        //check accuracy of latent outlier-indicator posterior samples
        final List<ACNVCopyRatioModeller.OutlierIndicators> outlierIndicatorSamples =
                modeller.getOutlierIndicatorsSamples();
        int numIndicatorsCorrect = 0;
        final int numIndicatorSamples = outlierIndicatorSamples.size();
        final List<Integer> outlierIndicatorsTruthAsInt = loadList(OUTLIER_INDICATORS_TRUTH_FILE, Integer::parseInt);
        final List<Boolean> outlierIndicatorsTruth =
                outlierIndicatorsTruthAsInt.stream().map(i -> i == 1).collect(Collectors.toList());
        for (int target = 0; target < targetCoverages.size(); target++) {
            int numSamplesOutliers = 0;
            for (ACNVCopyRatioModeller.OutlierIndicators sample : outlierIndicatorSamples) {
                if (sample.getOutlierIndicator(target)) {
                    numSamplesOutliers++;
                }
            }
            //take predicted state of indicator to be given by the majority of samples
            if ((numSamplesOutliers >= numIndicatorSamples / 2.) == outlierIndicatorsTruth.get(target)) {
                numIndicatorsCorrect++;
            }
        }
        final double fractionOfOutlierIndicatorsCorrect = (double) numIndicatorsCorrect / targetCoverages.size();
        Assert.assertTrue(fractionOfOutlierIndicatorsCorrect >= 0.95);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testMissingData() {
        final Genome genome = new Genome(Collections.emptyList(), Collections.emptyList(), SAMPLE_NAME);
        final SegmentedModel segmentedModel = new SegmentedModel(SEGMENT_FILE, genome);
        new ACNVCopyRatioModeller(segmentedModel);
    }
}