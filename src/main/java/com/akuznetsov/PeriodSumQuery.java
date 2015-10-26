package com.akuznetsov;


import org.apache.lucene.index.*;
import org.apache.lucene.queries.CustomScoreProvider;
import org.apache.lucene.search.*;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.ToStringUtils;

import java.io.IOException;
import java.util.*;

import static org.apache.lucene.util.NumericUtils.prefixCodedToLong;

/**
 * Created by alexander on 26.10.15.
 */
public class PeriodSumQuery extends Query {

    private Query subQuery;
    int start;
    int end;

    /**
     * Create a CustomScoreQuery over input subQuery.
     *
     * @param subQuery the sub query whose scored is being customized. Must not be null.
     */
    public PeriodSumQuery(Query subQuery, int start, int end) {
        this.subQuery = subQuery;
        this.start = start;
        this.end = end;
    }

    /*(non-Javadoc) @see org.apache.lucene.search.Query#rewrite(org.apache.lucene.index.IndexReader) */
    @Override
    public Query rewrite(IndexReader reader) throws IOException {
        PeriodSumQuery clone = null;

        final Query sq = subQuery.rewrite(reader);
        if (sq != subQuery) {
            clone = clone();
            clone.subQuery = sq;
        }
        return (clone == null) ? this : clone;
    }

    /*(non-Javadoc) @see org.apache.lucene.search.Query#extractTerms(java.util.Set) */
    @Override
    public void extractTerms(Set<Term> terms) {
        subQuery.extractTerms(terms);
    }

    /*(non-Javadoc) @see org.apache.lucene.search.Query#clone() */
    @Override
    public PeriodSumQuery clone() {
        PeriodSumQuery clone = (PeriodSumQuery) super.clone();
        clone.subQuery = subQuery.clone();
        return clone;
    }

    /* (non-Javadoc) @see org.apache.lucene.search.Query#toString(java.lang.String) */
    @Override
    public String toString(String field) {
        StringBuilder sb = new StringBuilder("Sum of period").append("(");
        sb.append(subQuery.toString(field));
        return sb.toString() + ToStringUtils.boost(getBoost());
    }

    /**
     * Returns true if <code>o</code> is equal to this.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!super.equals(o))
            return false;
        if (getClass() != o.getClass()) {
            return false;
        }
        PeriodSumQuery other = (PeriodSumQuery) o;
        if (this.getBoost() != other.getBoost() ||
                !this.subQuery.equals(other.subQuery)) {
            return false;
        }
        return true;
    }

    /**
     * Returns a hash code value for this object.
     */
    @Override
    public int hashCode() {
        return (getClass().hashCode() + subQuery.hashCode())
                ^ Float.floatToIntBits(getBoost());
    }

    /**
     * Returns a {@link CustomScoreProvider} that calculates the custom scores
     * for the given {@link IndexReader}. The default implementation returns a default
     * implementation as specified in the docs of {@link CustomScoreProvider}.
     *
     * @since 2.9.2
     */
    protected CustomScoreProvider getCustomScoreProvider(AtomicReaderContext context) throws IOException {
        return new PeriodSumScoreProvider(context, start, end);
    }

    //=========================== W E I G H T ============================

    private class CustomWeight extends Weight {
        Weight subQueryWeight;
        float queryWeight;

        public CustomWeight(IndexSearcher searcher) throws IOException {
            this.subQueryWeight = subQuery.createWeight(searcher);
        }

        @Override
        public Query getQuery() {
            return subQuery;
        }

        @Override
        public float getValueForNormalization() throws IOException {
            return subQueryWeight.getValueForNormalization();
        }

        /*(non-Javadoc) @see org.apache.lucene.search.Weight#normalize(float) */
        @Override
        public void normalize(float norm, float topLevelBoost) {
            // note we DONT incorporate our boost, nor pass down any topLevelBoost
            // (e.g. from outer BQ), as there is no guarantee that the CustomScoreProvider's
            // function obeys the distributive law... it might call sqrt() on the subQuery score
            // or some other arbitrary function other than multiplication.
            // so, instead boosts are applied directly in score()
            subQueryWeight.normalize(norm, 1f);
            queryWeight = topLevelBoost * getBoost();
        }

        @Override
        public Scorer scorer(AtomicReaderContext context, boolean scoreDocsInOrder, boolean topScorer, Bits acceptDocs) throws IOException {
            // Pass true for "scoresDocsInOrder", because we
            // require in-order scoring, even if caller does not,
            // since we call advance on the valSrcScorers.  Pass
            // false for "topScorer" because we will not invoke
            // score(Collector) on these scorers:
            Scorer subQueryScorer = subQueryWeight.scorer(context, scoreDocsInOrder, topScorer, acceptDocs);
            if (subQueryScorer == null) {
                return null;
            }
            return new CustomScorer(PeriodSumQuery.this.getCustomScoreProvider(context), this, queryWeight, subQueryScorer);
        }

        @Override
        public Explanation explain(AtomicReaderContext context, int doc) throws IOException {
            Explanation explain = doExplain(context, doc);
            return explain == null ? new Explanation(0.0f, "no matching docs") : explain;
        }

        private Explanation doExplain(AtomicReaderContext info, int doc) throws IOException {
            Explanation subQueryExpl = subQueryWeight.explain(info, doc);
            if (!subQueryExpl.isMatch()) {
                return subQueryExpl;
            }

            Explanation customExp = PeriodSumQuery.this.getCustomScoreProvider(info).customExplain(doc, subQueryExpl, new Explanation[]{});
            float sc = getBoost() * customExp.getValue();
            Explanation res = new ComplexExplanation(
                    true, sc, PeriodSumQuery.this.toString() + ", product of:");
            res.addDetail(customExp);
            res.addDetail(new Explanation(getBoost(), "queryBoost")); // actually using the q boost as q weight (== weight value)
            return res;
        }

    }


    //=========================== S C O R E R ============================

    /**
     * A scorer that applies a (callback) function on scores of the subQuery.
     */
    private class CustomScorer extends Scorer {
        private final float qWeight;
        private final Scorer subQueryScorer;
        private final CustomScoreProvider provider;

        // constructor
        private CustomScorer(CustomScoreProvider provider, CustomWeight w, float qWeight,
                             Scorer subQueryScorer) {
            super(w);
            this.qWeight = qWeight;
            this.subQueryScorer = subQueryScorer;
            this.provider = provider;
        }

        @Override
        public int nextDoc() throws IOException {
            return subQueryScorer.nextDoc();
        }

        @Override
        public int docID() {
            return subQueryScorer.docID();
        }

        /*(non-Javadoc) @see org.apache.lucene.search.Scorer#score() */
        @Override
        public float score() throws IOException {
            return qWeight * provider.customScore(subQueryScorer.docID(), subQueryScorer.score(), null);
        }

        @Override
        public int freq() throws IOException {
            return subQueryScorer.freq();
        }

        @Override
        public Collection<ChildScorer> getChildren() {
            return Collections.singleton(new ChildScorer(subQueryScorer, "CUSTOM"));
        }

        @Override
        public int advance(int target) throws IOException {
            return subQueryScorer.advance(target);
        }

        @Override
        public long cost() {
            return subQueryScorer.cost();
        }
    }

    @Override
    public Weight createWeight(IndexSearcher searcher) throws IOException {
        return new CustomWeight(searcher);
    }

}

class PeriodSumScoreProvider extends CustomScoreProvider {

    int start;
    int end;

    public PeriodSumScoreProvider(AtomicReaderContext context, int start, int end) {
        super(context);
        this.start = start;
        this.end = end;
    }

    @Override
    public float customScore(int doc, float subQueryScore, float[] valSrcScores) throws IOException {
        return periodSum(doc);
    }

    @Override
    public float customScore(int doc, float subQueryScore, float valSrcScore) throws IOException {
        return periodSum(doc);
    }

    private float periodSum(int doc) throws IOException {
        AtomicReader reader = context.reader();
        SortedSetDocValues intervals = reader.getSortedSetDocValues("intervals");
        intervals.setDocument(doc);

        double sum = 0;
        long nextOrd;
        while ((nextOrd = intervals.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
            BytesRef bytes = new BytesRef();
            intervals.lookupOrd(nextOrd, bytes);
            Long interaval = prefixCodedToLong(bytes);
            sum += Math.max(0, Math.min(end, interaval / 20088) - Math.max(start, interaval % 20088));
        }

        return (float) sum;
    }

    @Override
    public Explanation customExplain(int doc, Explanation subQueryExpl, Explanation[] valSrcExpls) throws IOException {
        AtomicReader reader = context.reader();
        SortedSetDocValues intervals = reader.getSortedSetDocValues("intervals");
        intervals.setDocument(doc);

        double sum = 0;
        long nextOrd;
        List<Explanation> details = new ArrayList<Explanation>();
        while ((nextOrd = intervals.nextOrd()) != SortedSetDocValues.NO_MORE_ORDS) {
            BytesRef bytes = new BytesRef();
            intervals.lookupOrd(nextOrd, bytes);
            Long interaval = prefixCodedToLong(bytes);
            long score = Math.max(0, Math.min(end, interaval / 20088) - Math.max(start, interaval % 20088));
            sum += score;
            details.add(new Explanation(score, "Sub interval"));
        }
        Explanation exp = new Explanation((float) sum, "score sum of:");
        for (Explanation e : details) {
            exp.addDetail(e);
        }
        return exp;
    }

    @Override
    public Explanation customExplain(int doc, Explanation subQueryExpl, Explanation valSrcExpl) throws IOException {
        Explanation[] explanations = {valSrcExpl};
        return customExplain(doc, subQueryExpl, explanations);
    }


}

