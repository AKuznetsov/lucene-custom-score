package com.akuznetsov;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.util.Version;

import java.io.IOException;

/**
 * Created by alexander on 26.10.15.
 */
public class ExampleOfPeriodSum {


    public static void main(String[] args) throws IOException {
        IndexWriterConfig indexWriterConfig = new IndexWriterConfig(Version.LUCENE_46, new StandardAnalyzer(Version.LUCENE_46));
        Directory directory = new RAMDirectory();
        IndexWriter indexWriter = new IndexWriter(directory, indexWriterConfig);

        addDoc(indexWriter, "one period", 20088 * 10L + 5L);
        addDoc(indexWriter, "two period", 20088 * 10L + 5L, 20088 * 15L + 12L);
        addDoc(indexWriter, "big period", 20088 * 1000L + 500L);
        indexWriter.commit();

        search(indexWriter, 0, 13);
        search(indexWriter, 10, 600);
    }

    private static void search(IndexWriter indexWriter, int start, int end) throws IOException {
        final IndexReader indexReader = DirectoryReader.open(indexWriter, false);
        IndexSearcher indexSearcher = new IndexSearcher(indexReader);
        PeriodSumQuery periodSumQuery = new PeriodSumQuery(
                new TermQuery(new Term("all", "all")),
                start,
                end);
        final TopDocs search = indexSearcher.search(periodSumQuery, 100);
        System.out.println("Max " + search.scoreDocs.length + " " + search.getMaxScore());
        for (ScoreDoc sd : search.scoreDocs) {
            Document document = indexReader.document(sd.doc);
            System.out.println(sd.doc + " " + sd.score + " " + document.getField("name").stringValue());
        }
    }


    private static void addDoc(IndexWriter indexWriter, String value, Long... periods) throws IOException {
        Document doc = new Document();
        doc.add(new TextField("name", value, Field.Store.YES));
        doc.add(new TextField("all", "all", Field.Store.NO));
        for (Long p : periods) {
            BytesRef bytesRef = new BytesRef();
            NumericUtils.longToPrefixCoded(p, 0, bytesRef);
            doc.add(new SortedSetDocValuesField("intervals", bytesRef));
        }
        indexWriter.addDocument(doc);
    }
}
