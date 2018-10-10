package ai.h2o.automl.targetencoding;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import water.*;
import water.fvec.*;
import water.rapids.Rapids;
import water.rapids.Val;
import water.util.TwoDimTable;

import java.util.Map;

import static org.junit.Assert.*;

public class TargetEncodingTest extends TestUtil {


  @BeforeClass public static void setup() {
    stall_till_cloudsize(1);
  }

  private Frame fr = null;


    @Test(expected = IllegalStateException.class)
    public void targetEncoderPrepareEncodingFrameValidationDataIsNotNullTest() {

        TargetEncoder tec = new TargetEncoder();
        String[] teColumns = {"0"};

        tec.prepareEncodingMap(null, teColumns, "2", null);
    }


    @Test(expected = IllegalStateException.class)
    public void targetEncoderPrepareEncodingFrameValidationTEColumnsIsNotEmptyTest() {

        TargetEncoder tec = new TargetEncoder();
        String[] teColumns = {};

        tec.prepareEncodingMap(null, teColumns, "2", null);

    }

    @Test
    public void changeKeyFrameTest() {
      Frame res = null;
      try {
        fr = new TestFrameBuilder()
                .withName("testFrame")
                .withColNames("ColA")
                .withVecTypes(Vec.T_NUM)
                .withDataForCol(0, ard(1, 2))
                .build();
        String tree = "( append testFrame 42 'appended' )";
        Val val = Rapids.exec(tree);
        res = val.getFrame();
        res._key = fr._key;
        DKV.put(fr._key, res);

      } finally {
        res.delete();
      }
    }

    @Test
    public void allTEColumnsAreCategoricalTest() {

        TestFrameBuilder baseBuilder = new TestFrameBuilder()
                .withName("testFrame")
                .withColNames("ColA", "ColB", "ColC")
                .withDataForCol(0, ar("1", "0"))
                .withDataForCol(2, ar("1", "6"));

        TargetEncoder tec = new TargetEncoder();
        int[] teColumns = {0, 1};
        Map<String, Frame> encodingMap = null;

        fr = baseBuilder
                .withDataForCol(1, ar(0, 1))
                .withVecTypes(Vec.T_CAT, Vec.T_NUM, Vec.T_CAT)
                .build();
        try {
          tec.prepareEncodingMap(fr, teColumns, 2, null);
            fail();
        } catch (IllegalStateException ex) {
            assertEquals("Argument 'columnsToEncode' should contain only names of categorical columns", ex.getMessage());
        }

        fr = baseBuilder
                .withDataForCol(1, ar("a", "b"))
                .withVecTypes(Vec.T_CAT, Vec.T_CAT, Vec.T_CAT)
                .build();

        try {
          encodingMap = tec.prepareEncodingMap(fr, teColumns, 2, null);
        } catch (IllegalStateException ex) {
            fail(String.format("All columns were categorical but something else went wrong: %s", ex.getMessage()));
        }

      encodingMapCleanUp(encodingMap);
    }

    @Test
    public void prepareEncodingMapWithoutFoldColumnCaseTest() {
      Scope.enter();
      Map<String, Frame> targetEncodingMap = null;
      try {
        fr = new TestFrameBuilder()
                .withName("testFrame")
                .withColNames("ColA", "ColB", "ColC")
                .withVecTypes(Vec.T_CAT, Vec.T_NUM, Vec.T_CAT)
                .withDataForCol(0, ar("a", "b", "b", "b"))
                .withDataForCol(1, ard(1, 1, 4, 7))
                .withDataForCol(2, ar("2", "6", "6", "6"))
                .build();

        TargetEncoder tec = new TargetEncoder();
        int[] teColumns = {0};

        targetEncodingMap = tec.prepareEncodingMap(fr, teColumns, 2);

        Frame colAEncoding = targetEncodingMap.get("ColA");
        Scope.track(colAEncoding);

        assertVecEquals(vec(0, 3), colAEncoding.vec(1), 1e-5);
        assertVecEquals(vec(1, 3), colAEncoding.vec(2), 1e-5);
      } finally {
        Scope.exit();
      }

    }

  @Test // Test that we are not introducing keys leakage when we reassign within if-statement
  public void ifStatementsWithFramesTest() {
    Scope.enter();
    try {
      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA", "ColB")
              .withVecTypes(Vec.T_CAT, Vec.T_CAT)
              .withDataForCol(0, ar("a", "b"))
              .withDataForCol(1, ar("yes", "no"))
              .build();

      boolean flag = false;
      TargetEncoder tec = new TargetEncoder();
      Frame dataWithAllEncodings = null ;
      if(flag) {
        Frame dataWithEncodedTarget = tec.ensureTargetColumnIsNumericOrBinaryCategorical(fr, "ColB");
        dataWithAllEncodings = dataWithEncodedTarget.deepCopy(Key.make().toString());
        DKV.put(dataWithAllEncodings);

        assertVecEquals(dataWithAllEncodings.vec("ColB"), vec(1, 0), 1E-5);
      }
      else {
        dataWithAllEncodings = fr;
      }

      dataWithAllEncodings.delete();
    } finally {
      Scope.exit();
    }
  }

    @Test
    public void imputeWithMeanTest() {
      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA")
              .withVecTypes(Vec.T_STR)
              .withDataForCol(0, ar("1", "2", null))
              .build();

      TargetEncoder tec = new TargetEncoder();

      // We have to do this trick because we cant initialize array with `null` values.
      Vec strVec = fr.vec("ColA");
      Vec numericVec = strVec.toNumericVec();
      fr.replace(0, numericVec);

      Frame withImputed = tec.imputeWithMean(fr, 0);
      Vec expected = dvec(1, 2, 1.5);
      Vec resultVec = withImputed.vec(0);
      assertVecEquals(expected, resultVec, 1e-5);

      expected.remove();
      strVec.remove();
      resultVec.remove();
      withImputed.delete();
      numericVec.remove();
    }

    @Test
    public void rbindTest() {
      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA")
              .withVecTypes(Vec.T_NUM)
              .withDataForCol(0, ar(1))
              .build();

      TargetEncoder tec = new TargetEncoder();

      Frame result = tec.rBind(null, fr);
      assertEquals(fr._key, result._key);

      Frame fr2 = new TestFrameBuilder()
              .withName("testFrame2")
              .withColNames("ColA")
              .withVecTypes(Vec.T_NUM)
              .withDataForCol(0, ar(42))
              .build();

      Frame result2 = tec.rBind(fr, fr2);

      assertEquals(1, result2.vec("ColA").at(0), 1e-5);
      assertEquals(42, result2.vec("ColA").at(1), 1e-5);

      fr2.delete();
      result2.delete();
    }

    @Test
    public void calculateSingleNumberResultTest() {
        fr = new TestFrameBuilder()
                .withName("testFrame")
                .withColNames("ColA")
                .withVecTypes(Vec.T_NUM)
                .withDataForCol(0, ard(1, 2, 3))
                .build();
        String tree = "(sum (cols testFrame [0.0] ))";
        Val val = Rapids.exec(tree);
        assertEquals(val.getNum(), 6.0, 1e-5);
    }

    @Test
    public void calculateGlobalMeanTest() {
        fr = new TestFrameBuilder()
                .withName("testFrame")
                .withColNames("numerator", "denominator")
                .withVecTypes(Vec.T_NUM, Vec.T_NUM)
                .withDataForCol(0, ard(1, 2, 3))
                .withDataForCol(1, ard(3, 4, 5))
                .build();
        TargetEncoder tec = new TargetEncoder();
        double result = tec.calculateGlobalMean(fr);

        assertEquals(result, 0.5, 1e-5);
    }

    @Test
    public void groupByTEColumnAndAggregateTest() {
      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("teColumn", "numerator", "denominator")
              .withVecTypes(Vec.T_CAT, Vec.T_NUM, Vec.T_NUM)
              .withDataForCol(0, ar("a", "a", "b"))
              .withDataForCol(1, ard(1, 2, 3))
              .withDataForCol(2, ard(3, 4, 5))
              .build();
      TargetEncoder tec = new TargetEncoder();
      Frame result = tec.groupByTEColumnAndAggregate(fr, 0);
      printOutFrameAsTable(result);

      Vec expectedNum = vec(3, 3);
      assertVecEquals(expectedNum, result.vec("sum_numerator"), 1e-5);
      Vec expectedDen = vec(7, 5);
      assertVecEquals(expectedDen, result.vec("sum_denominator"), 1e-5);

      result.delete();
      expectedNum.remove();
      expectedDen.remove();
    }


    @Test
    public void mapOverTheFrameWithImmutableApproachTest() {
      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA", "ColB", "ColC")
              .withVecTypes(Vec.T_CAT, Vec.T_NUM, Vec.T_NUM)
              .withDataForCol(0, ar("a", "b", "c"))
              .withDataForCol(1, ar(1,2,3))
              .withDataForCol(2, ar(4,5,6))
              .build();

      Frame oneColumnMultipliedOnly = new CalculatedColumnTask(1).doAll(Vec.T_NUM, fr).outputFrame();

      printOutFrameAsTable(oneColumnMultipliedOnly);
      assertEquals(1, oneColumnMultipliedOnly.numCols());

      Vec expectedVec = vec(2, 4, 6);
      Vec outcomeVec = oneColumnMultipliedOnly.vec(0);
      assertVecEquals(expectedVec, outcomeVec, 1e-5);

      expectedVec.remove();
      outcomeVec.remove();
      oneColumnMultipliedOnly.delete();
    }

    public static class CalculatedColumnTask extends MRTask<CalculatedColumnTask> {
      long columnIndex;

      public CalculatedColumnTask(long columnIndex) {
        this.columnIndex = columnIndex;
      }

      @Override
      public void map(Chunk cs[], NewChunk ncs[]) {
        for (int col = 0; col < cs.length; col++) {
          if (col == columnIndex) {
            Chunk c = cs[col];
            NewChunk nc = ncs[0];
            for (int i = 0; i < c._len; i++)
              nc.addNum(c.at8(i) * 2);
          }


        }
      }
    }

    @Test
    public void mutateOnlyParticularColumnsOfTheFrameTest() {
        fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA", "ColB", "ColC")
              .withVecTypes(Vec.T_CAT, Vec.T_NUM, Vec.T_NUM)
              .withDataForCol(0, ar("a", "b", "c"))
              .withDataForCol(1, ar(1,2,3))
              .withDataForCol(2, ar(4,5,6))
              .build();

      new TestMutableTask(1).doAll(fr);

      printOutFrameAsTable(fr);
      assertEquals(3, fr.numCols());

      Vec expected = vec(2, 4, 6);
      assertVecEquals(expected, fr.vec(1), 1e-5);

      expected.remove();
    }


    public static class TestMutableTask extends MRTask<TestMutableTask> {
      long columnIndex;
      public TestMutableTask(long columnIndex) {
        this.columnIndex = columnIndex;
      }
      @Override
      public void map(Chunk cs[]) {
        for (int col = 0; col < cs.length; col++) {
          if(col == columnIndex) {
            for (int i = 0; i < cs[col]._len; i++) {
              long value = cs[col].at8(i);
              cs[col].set(i, value * 2);
            }
          }
        }
      }
    }

    // ----------------------------- blended average -----------------------------------------------------------------//
    @Test
    public void calculateAndAppendBlendedTEEncodingTest() {

      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA", "ColB")
              .withVecTypes(Vec.T_CAT, Vec.T_CAT)
              .withDataForCol(0, ar("a", "b", "a"))
              .withDataForCol(1, ar("yes", "no", "yes"))
              .build();
      TargetEncoder tec = new TargetEncoder();
      int[] teColumns = {0};
      Map<String, Frame> targetEncodingMap = tec.prepareEncodingMap(fr, teColumns, 1);

      Frame merged = tec.mergeByTEColumn(fr, targetEncodingMap.get("ColA"), 0, 0);

      Frame resultWithEncoding = tec.calculateAndAppendBlendedTEEncoding(merged, targetEncodingMap.get("ColA"), "ColB", "targetEncoded");

      // k <- 20
      // f <- 10
      // global_mean <- sum(x_map$numerator)/sum(x_map$denominator)
      // lambda <- 1/(1 + exp((-1)* (te_frame$denominator - k)/f))
      // te_frame$target_encode <- ((1 - lambda) * global_mean) + (lambda * te_frame$numerator/te_frame$denominator)

      double globalMean = 2.0 / 3;
      double lambda1 = 1.0 / (1.0 + (Math.exp((20.0 - 2) / 10)));
      double te1 = (1.0 - lambda1) * globalMean + (lambda1 * 2 / 2);

      double lambda2 = 1.0 / (1 + Math.exp((20.0 - 1) / 10));
      double te2 = (1.0 - lambda2) * globalMean + (lambda2 * 0 / 1);

      double lambda3 = 1.0 / (1.0 + (Math.exp((20.0 - 2) / 10)));
      double te3 = (1.0 - lambda3) * globalMean + (lambda3 * 2 / 2);

      assertEquals(te1, resultWithEncoding.vec(4).at(0), 1e-5);
      assertEquals(te3, resultWithEncoding.vec(4).at(1), 1e-5);
      assertEquals(te2, resultWithEncoding.vec(4).at(2), 1e-5);

      encodingMapCleanUp(targetEncodingMap);
      merged.delete();
      resultWithEncoding.delete();

    }

    @Test
    public void calculateAndAppendBlendedTEEncodingPerformanceTest() {
      long startTimeEncoding = System.currentTimeMillis();

      int numberOfRuns = 10;
      for(int i = 0; i < numberOfRuns; i ++) {
        Frame fr = new TestFrameBuilder()
                .withName("testFrame")
                .withColNames("numerator", "denominator", "target")
                .withVecTypes(Vec.T_NUM, Vec.T_NUM, Vec.T_CAT)
                .withRandomDoubleDataForCol(0, 1000000, 0, 50)
                .withRandomDoubleDataForCol(1, 1000000, 1, 100)
                .withRandomBinaryDataForCol(2, 1000000)
                .build();

        BlendingParams blendingParams = new BlendingParams(20, 10);


        Frame frameWithBlendedEncodings = new TargetEncoder.CalcEncodingsWithBlending(0, 1, 42, blendingParams).doAll(Vec.T_NUM, fr).outputFrame();
        fr.add("encoded", frameWithBlendedEncodings.anyVec());
        fr.delete();
      }
      long finishTimeEncoding = System.currentTimeMillis();
      System.out.println("Calculation of encodings took(ms): " + (finishTimeEncoding - startTimeEncoding));
      System.out.println("Avg calculation of encodings took(ms): " + (double)(finishTimeEncoding - startTimeEncoding) / numberOfRuns);

    }

    @Test
    public void blendingTest() {
      //      //TODO more tests for blending
    }

    @Test
    public void vecESPCTest() {
      Vec vecOfALengthTwo = vec(1, 0);
      long[] espcForLengthTwo = {0, 2};
      assertArrayEquals(espcForLengthTwo, Vec.ESPC.espc(vecOfALengthTwo));

      Vec vecOfALengthThree = vec(1, 0, 3);
      long[] espcForVecOfALengthThree = {0, 3};
      assertArrayEquals(espcForVecOfALengthThree, Vec.ESPC.espc(vecOfALengthThree));

      vecOfALengthTwo.remove();
      vecOfALengthThree.remove();
    }

  // --------------------------- Merging tests -----------------------------------------------------------------------//

    @Test
    public void mergingByTEAndFoldTest() {

      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA", "ColB")
              .withVecTypes(Vec.T_CAT, Vec.T_NUM)
              .withDataForCol(0, ar("a", "b", "a"))
              .withDataForCol(1, ar(1,1,2))
              .build();

      Frame holdoutEncodingMap = new TestFrameBuilder()
              .withName("holdoutEncodingMap")
              .withColNames("ColA", "ColC", "foldValueForMerge")
              .withVecTypes(Vec.T_CAT, Vec.T_STR, Vec.T_NUM)
              .withDataForCol(0, ar("a", "b", "a"))
              .withDataForCol(1, ar("yes", "no", "yes"))
              .withDataForCol(2, ar(1, 2, 2))
              .build();

      TargetEncoder tec = new TargetEncoder();

      Frame merged = tec.mergeByTEAndFoldColumns(fr, holdoutEncodingMap, 0, 1, 0);
      printOutFrameAsTable(merged);
      Vec expecteds = svec("yes", "yes", null);
      assertStringVecEquals(expecteds, merged.vec("ColC"));

      expecteds.remove();
      merged.delete();
      holdoutEncodingMap.delete();
    }

    @Test
    public void AddNoiseLevelTest() {

      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA", "ColB", "ColC")
              .withVecTypes(Vec.T_NUM, Vec.T_NUM, Vec.T_NUM)
              .withDataForCol(0, ard(1, 2, 3))
              .withDataForCol(1, ard(1, 2, 3))
              .withDataForCol(2, ard(1, 2, 3))
              .build();

      double noiseLevel = 1e-2;
      TargetEncoder tec = new TargetEncoder();

      tec.addNoise(fr, "ColA", noiseLevel, 1234);
      tec.addNoise(fr, "ColB", noiseLevel, 5678);
      tec.addNoise(fr, "ColC", noiseLevel, 1234);
      Vec expected = vec(1, 2, 3);
      assertVecEquals(expected, fr.vec(0), 1e-2);

      try {
        assertVecEquals(fr.vec(0), fr.vec(1), 0.0);
        fail();
      } catch (AssertionError ex){ }

      //Vectors with the noises generated from the same seeds should be equal
      assertVecEquals(fr.vec(0), fr.vec(2), 0.0);

      expected.remove();
    }

    @Test
    public void getColumnNamesByIndexesTest() {
        fr = new TestFrameBuilder()
                .withName("testFrame")
                .withColNames("ColA", "ColB", "ColC")
                .withVecTypes(Vec.T_CAT, Vec.T_NUM, Vec.T_CAT)
                .withDataForCol(0, ar("a", "b"))
                .withDataForCol(1, ard(1, 1))
                .withDataForCol(2, ar("2", "6"))
                .build();

        TargetEncoder tec = new TargetEncoder();
        int[] columns = ari(0,2);
        String [] columnNames = tec.getColumnNamesBy(fr, columns);
        assertEquals("ColA", columnNames[0]);
        assertEquals("ColC", columnNames[1]);
    }

    @Test
    public void ensureTargetColumnIsNumericOrBinaryCategoricalTest() {
      Scope.enter();
      try {
        fr = new TestFrameBuilder()
                .withName("testFrame")
                .withColNames("ColA", "ColB", "ColC", "ColD")
                .withVecTypes(Vec.T_CAT, Vec.T_NUM, Vec.T_STR, Vec.T_CAT)
                .withDataForCol(0, ar("a", "b", "c", "d"))
                .withDataForCol(1, ard(1, 2, 3, 4))
                .withDataForCol(2, ar("2", "6", "6", "6"))
                .withDataForCol(3, ar("2", "6", "6", null))
                .build();

        TargetEncoder tec = new TargetEncoder();

        try {
          tec.ensureTargetColumnIsNumericOrBinaryCategorical(fr, 0);
          fail();
        } catch (Exception ex) {
          assertEquals("`target` must be a binary vector", ex.getMessage());
        }

        try {
          tec.ensureTargetColumnIsNumericOrBinaryCategorical(fr, 2);
          fail();
        } catch (Exception ex) {
          assertEquals("`target` must be a numeric or binary vector", ex.getMessage());
        }

        // Check that numerical column is ok
        Frame tmp3 = tec.ensureTargetColumnIsNumericOrBinaryCategorical(fr, 1);
        Scope.track(tmp3);

        // Check that binary categorical is ok (transformation is checked in another test)
        Frame tmp4 = tec.ensureTargetColumnIsNumericOrBinaryCategorical(fr, 3);
        Scope.track(tmp4);

        assertTrue(tmp4.vec(3).isNA(3));
      } finally {
        Scope.exit();
      }
    }

    @Test
    public void ensureTargetEncodingAndRemovingNAsWorkingTogetherTest() {
      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA")
              .withVecTypes(Vec.T_CAT)
              .withDataForCol(0, ar("2", "6", "6", null))
              .build();

      TargetEncoder tec = new TargetEncoder();

      Frame tmp1 = tec.filterOutNAsFromTargetColumn(fr, 0);
      Frame tmp2 = tec.ensureTargetColumnIsNumericOrBinaryCategorical(tmp1, 0);

      Vec expected = vec(0, 1, 1);
      assertVecEquals(expected, tmp2.vec(0), 1e-5);

      expected.remove();
      tmp1.delete();
      tmp2.delete();
    }

    @Test
    public void ensureTargetColumnIsNumericOrBinaryCategoricalOrderTest() {
      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA", "ColB")
              .withVecTypes(Vec.T_CAT ,Vec.T_NUM)
              .withDataForCol(0, ar("NO", "YES", "NO"))
              .withDataForCol(1, ar(1, 2, 3))
              .build();

      Frame fr2 = new TestFrameBuilder()
              .withName("testFrame2")
              .withColNames("ColA2", "ColB2")
              .withVecTypes(Vec.T_CAT, Vec.T_NUM)
              .withDataForCol(0, ar("YES", "NO", "NO"))
              .withDataForCol(1, ar(1, 2, 3))
              .build();

      TargetEncoder tec = new TargetEncoder();

      try {
        assertArrayEquals(fr.vec(0).domain(), fr2.vec(0).domain());
        fail();
      } catch (AssertionError ex) {
        assertEquals("arrays first differed at element [0]; expected:<[NO]> but was:<[YES]>", ex.getMessage());
      }

      Frame encoded = tec.transformBinaryTargetColumn(fr, 0);
      Frame encoded2 = tec.transformBinaryTargetColumn(fr2, 0);

      // Checking that Label Encoding will not assign 0 label to the first category it encounters. We are sorting domain to make order consistent.
      assertEquals(0, encoded.vec(0).at(0), 1e-5);
      assertEquals(1, encoded2.vec(0).at(0), 1e-5);
      fr.delete();
      fr2.delete();
      encoded.delete();
      encoded2.delete();
    }

  @Test
  public void isBinaryTest() {
    fr = new TestFrameBuilder()
            .withName("testFrame")
            .withColNames("ColA", "ColB")
            .withVecTypes(Vec.T_CAT, Vec.T_NUM)
            .withDataForCol(0, ar("NO", "YES", "NO"))
            .withDataForCol(1, ard(0, 0.5, 1))
            .build();

    assertTrue(fr.vec(0).isBinary());
    assertFalse(fr.vec(1).isBinary());
  }

    @Ignore
    @Test
    public void ensureTargetColumnIsNumericOrBinaryCategoricalUnderrepresentedClassTest() {
      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA", "ColB")
              .withVecTypes(Vec.T_CAT ,Vec.T_NUM)
              .withDataForCol(0, ar("NO")) //case 2: ("yes") let say all the examples are "yes" // case 3: YES
              .withDataForCol(1, ar(111))
              .build();

      Frame fr2 = new TestFrameBuilder()
              .withName("testFrame2")
              .withColNames("ColA2", "ColB2")
              .withVecTypes(Vec.T_CAT, Vec.T_NUM)
              .withDataForCol(0, ar("YES")) //case 2: ("no", "yes")  in validation set we will be comparing YESs with NOs // case 3: NO - we will think that all examples are of 0 class.
              .withDataForCol(1, ar(222))
              .build();

      // TODO consider all possible combinations. Some of them does not make sense but still we should check them.

      fr2.delete();
    }

    @Test
    public void transformBinaryTargetColumnTest() {
        fr = new TestFrameBuilder()
                .withName("testFrame")
                .withColNames("ColA", "ColB", "ColC", "fold_column")
                .withVecTypes(Vec.T_CAT, Vec.T_NUM, Vec.T_CAT,  Vec.T_NUM)
                .withDataForCol(0, ar("a", "b"))
                .withDataForCol(1, ard(1, 1))
                .withDataForCol(2, ar("2", "6"))
                .withDataForCol(3, ar(1, 2))
                .build();

        TargetEncoder tec = new TargetEncoder();

        // So with TestFrameBuilder column is automatically seen as binary but it is not Numerical
        assertTrue(fr.vec(2).isBinary());
        assertTrue(fr.vec(2).isCategorical());
        assertFalse(fr.vec(2).isString());
        assertFalse(fr.vec(2).isNumeric());

        Frame res = tec.transformBinaryTargetColumn(fr, 2);

        Vec transformedVector = res.vec(2);

        assertTrue(transformedVector.isBinary());
        assertFalse(transformedVector.isCategorical());
        assertTrue(transformedVector.isNumeric());

        assertEquals(0, transformedVector.at(0), 1e-5);
        assertEquals(1, transformedVector.at(1), 1e-5);

        transformedVector.remove();
        res.delete();
    }

  // Can we do it simply ? with mutation?
    @Test
    public void appendingColumnsInTheLoopTest() {
      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA")
              .withVecTypes(Vec.T_NUM)
              .withDataForCol(0, ar(1,2))
              .build();

      Frame accFrame = fr.deepCopy(Key.make().toString());;
      DKV.put(accFrame);

      printOutFrameAsTable(accFrame, true, false);

      for(int i = 0 ; i < 3; i ++) {

        String tree = String.format("( append %s %d 'col_%d' )", accFrame._key, i, i);
        Frame withAppendedFrame = Rapids.exec(tree).getFrame();
        withAppendedFrame._key = Key.make();
        DKV.put(withAppendedFrame);

        accFrame.delete();
        accFrame = withAppendedFrame.deepCopy(Key.make().toString());
        DKV.put(accFrame);
        withAppendedFrame.delete();
        printOutFrameAsTable(accFrame);
      }

      accFrame.delete();
    }

    @Test
    public void filterOutByTest() {
      fr = new TestFrameBuilder()
              .withName("testFrame")
              .withColNames("ColA")
              .withVecTypes(Vec.T_STR)
              .withDataForCol(0, ar("SAN", "SFO"))
              .build();
      Frame res = filterOutBy(fr, 0, "SAN");
      res.delete();
    }

    @Test
    public void filterByTest() {
        fr = new TestFrameBuilder()
                .withName("testFrame")
                .withColNames("ColA")
                .withVecTypes(Vec.T_STR)
                .withDataForCol(0, ar("SAN", "SFO"))
                .build();
        Frame res = filterBy(fr, 0, "SAN");
        res.delete();
    }

    public Frame filterOutBy(Frame data, int columnIndex, String value)  {
        String tree = String.format("(rows %s  (!= (cols %s [%s] ) '%s' )  )", data._key, data._key, columnIndex, value);
        Val val = Rapids.exec(tree);
        Frame res = val.getFrame();
        res._key = data._key;
        DKV.put(res._key , res);
        return res;
    }

    public Frame filterBy(Frame data, int columnIndex, String value)  {
        String tree = String.format("(rows %s  (==(cols %s [%s] ) '%s' ) )", data._key, data._key, columnIndex, value);
        Val val = Rapids.exec(tree);
        Frame res = val.getFrame();
        res._key = data._key;
        DKV.put(res);
        return res;
    }

    @After
    public void afterEach() {
        if( fr!= null) fr.delete();
    }

    private void encodingMapCleanUp(Map<String, Frame> encodingMap) {
      for( Map.Entry<String, Frame> map : encodingMap.entrySet()) {
        map.getValue().delete();
      }
    }

    private void printOutFrameAsTable(Frame fr) {

        TwoDimTable twoDimTable = fr.toTwoDimTable();
        System.out.println(twoDimTable.toString(2, false));
    }

  private void printOutFrameAsTable(Frame fr, boolean full, boolean rollups) {

    TwoDimTable twoDimTable = fr.toTwoDimTable(0, 10000, rollups);
    System.out.println(twoDimTable.toString(2, full));
  }

  private void printOutColumnsMeta(Frame fr) {
    for (String header : fr.toTwoDimTable().getColHeaders()) {
      String type = fr.vec(header).get_type_str();
      int cardinality = fr.vec(header).cardinality();
      System.out.println(header + " - " + type + String.format("; Cardinality = %d", cardinality));

    }
  }
}
