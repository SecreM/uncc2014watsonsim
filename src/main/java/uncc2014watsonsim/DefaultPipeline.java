package uncc2014watsonsim;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.Properties;

import org.apache.uima.UIMAFramework;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.ResourceSpecifier;
import org.apache.uima.util.InvalidXMLException;
import org.apache.uima.util.XMLInputSource;
import org.postgresql.jdbc2.TimestampUtils;

import uncc2014watsonsim.researchers.*;
import uncc2014watsonsim.scorers.*;
import uncc2014watsonsim.search.*;
import static uncc2014watsonsim.StringUtils.getOrDie;

/** The standard Question Analysis pipeline.
 * 
 * The pipeline is central to the DeepQA framework.
 * It consists of {@link Searcher}s, {@link Researcher}s, {@link Scorer}s, and
 * a {@link Learner}.<p>
 * 
 * Each step in the pipeline takes and possibly transforms a {@link Question}.
 * {@link Question}s aggregate {@link Answer}s, and a correct {@link Answer} (if it is
 *     known).
 * {@link Answer}s aggregate scores (which are primitive doubles) and
 *     {@link Passage}s, and contain a candidate text.
 * {@link Passage}s aggregate more scores, and provide some utilities for
 *     processing the text they contain.<p>
 * 
 * A {@link Searcher} takes the {@link Question}, runs generic transformations
 *     on its text and runs a search engine on it. The Passages it creates are
 *     promoted into {@link Answer}s, where the Passage title is the candidate
 *     {@link Answer} text and each {@link Answer} has one Passage. The passage
 *     Searchers do the same but are optimized for taking {@link Answer}s and
 *     finding supporting evidence as Passages. In that case, the resulting
 *     Passages are not promoted.<p>
 * 
 * A {@link Researcher} takes a {@link Question} and performs a transformation
 *     on it. There is no contract regarding what it can do to the
 *     {@link Question}, so they can't be safely run in parallel and the order
 *     of execution matters. Read the source for an idea of the intended order.
 *     <p>
 * 
 * A {@link Scorer} takes a {@link Question} and generates scores for either
 *     {@link Answer}s or {@link Passage}s (inheriting from
 *     {@link AnswerScorer} or {@link PassageScorer} respectively.)<p>
 *
 */
public class DefaultPipeline {
	
	private final Timestamp run_start;
	private final Properties config = new Properties();
	private final Searcher[] searchers;
	private final Researcher[] early_researchers;
	private final Scorer[] scorers;
	private final Researcher[] late_researchers;
	
	/*
	 * Initialize UIMA. 
	 * Why here? We do not want to reinstantiate the Analysis engine each time.
	 * We also don't want to load the POS models each time we ask a new question. Here we can hold the AE for the 
	 * entire duration of the Pipeline's life.
	 */
	public AnalysisEngine uimaAE;
	
	/* {
		try{
			XMLInputSource uimaAnnotatorXMLInputSource = new XMLInputSource("src/main/java/uncc2014watsonsim/uima/qAnalysis/qAnalysisApplicationDescriptor.xml");
			final ResourceSpecifier specifier = UIMAFramework.getXMLParser().parseResourceSpecifier(uimaAnnotatorXMLInputSource);
			//Generate AE
			uimaAE = UIMAFramework.produceAnalysisEngine(specifier);
		}catch(IOException e){
			e.printStackTrace();
		} catch (InvalidXMLException e) {
			e.printStackTrace();
		} catch (ResourceInitializationException e) {
			e.printStackTrace();
		}
	}*/
	/* End UIMA */
	
	/**
	 * Start a pipeline with a new timestamp for the statistics dump
	 */
	public DefaultPipeline() {
		this(System.currentTimeMillis());
	}
	
	/**
	 * Start a pipeline with an existing timestamp
	 * @param millis Millis since the Unix epoch, as in currentTimeMillis()
	 */
	public DefaultPipeline(long millis) {
		run_start = new Timestamp(millis);

		// Read the configuration
		try {
			Reader s = new InputStreamReader(
					new FileInputStream("config.properties"), "UTF-8");
			config.load(s);
			s.close();
		} catch (IOException e) {
			System.err.println("Missing or broken 'config.properties'. "
					+ "Please create one by copying "
					+ "config.properties.sample.");
			throw new RuntimeException(e.getMessage());
		}
		
		/*
		 * Create the pipeline
		 */
		searchers = new Searcher[]{
			new LuceneSearcher(config),
			new IndriSearcher(config),
			// You may want to cache Bing results
			// new BingSearcher(config),
			new CachingSearcher(new BingSearcher(config), "bing")
		};
		early_researchers = new Researcher[]{
			new MediaWikiTrimmer(), // Before passage retrieval
			new RedirectSynonyms(),
			new HyphenTrimmer(),
			new Merger(),
			//new ChangeFitbAnswerToContentsOfBlanks(),
			new PassageRetrieval(config),
			new MediaWikiTrimmer(), // Rerun after passage retrieval
			new PersonRecognition(),
			new TagLAT(getOrDie(config, "sparql_url")),
		};
		scorers = new Scorer[]{
			//new LuceneRank(),
			//new LuceneScore(),
			//new IndriRank(),
			//new IndriScore(),
			//new BingRank(),
			//new GoogleRank(),
			new WordProximity(),
			new Correct(),
			new SkipBigram(),
			new PassageTermMatch(),
			new PassageCount(),
			new PassageQuestionLengthRatio(),
			new PercentFilteredWordsInCommon(),
			new AnswerInQuestionScorer(),
			//new ScorerIrene(), // TODO: Introduce something new
			new NGram(),
			//new LATTypeMatchScorer(),
			new LATCheck(),
			new WPPageViews(),
			//new RandomIndexingCosineSimilarity(),
			new DistSemCosQAScore(),
			//new DistSemCosQPScore(),
			//new WShalabyScorer(), // TODO: Introduce something new
			//new SentenceSimilarity(),
			//new CoreNLPSentenceSimilarity(),
		};
		late_researchers = new Researcher[]{
			new WekaTee(run_start),
			new CombineScores(),
			new StatsDump(run_start)
		};
	}
	
	public Question ask(String qtext) {
	    return ask(new Question(qtext));
	}
	
    /** Run the full standard pipeline */
	public Question ask(Question question) {
		// Query every engine
		for (Searcher s: searchers)
			question.addPassages(s.query(question.getRaw_text()));

		for (Researcher r : early_researchers)
			r.question(question);
    	
    	for (Researcher r : early_researchers)
    		r.complete();
    	

        for (Scorer s: scorers) {
        	s.scoreQuestion(question);
        }
        
        for (Researcher r : late_researchers)
			r.question(question);
    	
    	for (Researcher r : late_researchers)
    		r.complete();
        
        return question;
    }
}
