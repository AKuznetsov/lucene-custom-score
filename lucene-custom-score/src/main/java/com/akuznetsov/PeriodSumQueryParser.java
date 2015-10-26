package com.akuznetsov;

import org.apache.lucene.search.Query;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.SolrQueryParser;
import org.apache.solr.search.SyntaxError;

/**
 * Created by alexander on 26.10.15.
 */
public class PeriodSumQueryParser extends QParser {


    SolrQueryParser lparser;

    /**
     * Constructor for the QParser
     *
     * @param qstr               The part of the query string specific to this parser
     * @param localParams        The set of parameters that are specific to this QParser.  See http://wiki.apache.org/solr/LocalParams
     * @param params             The rest of the {@link org.apache.solr.common.params.SolrParams}
     * @param req                The original {@link org.apache.solr.request.SolrQueryRequest}.
     */
    public PeriodSumQueryParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
        super(qstr, localParams, params, req);
    }

    @Override
    public Query parse() throws SyntaxError {
        String qstr = getString();
        if (qstr == null || qstr.length() == 0) return null;

        String defaultField = getParam(CommonParams.DF);
        if (defaultField == null) {
            defaultField = getReq().getSchema().getDefaultSearchFieldName();
        }
        lparser = new SolrQueryParser(this, defaultField);

        lparser.setDefaultOperator
                (QueryParsing.getQueryParserDefaultOperator(getReq().getSchema(),
                        getParam(QueryParsing.OP)));

        final Query main = lparser.parse(qstr);

        Integer start = Integer.valueOf( getParam("interval_start"));
        Integer end = Integer.valueOf(getParam("interval_end"));


        PeriodSumQuery result = new PeriodSumQuery(main, start, end);
        return result;
    }


}
