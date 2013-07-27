
package no.priv.garshol.duke.genetic;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.io.IOException;

import no.priv.garshol.duke.Link;
import no.priv.garshol.duke.Record;
import no.priv.garshol.duke.Database;
import no.priv.garshol.duke.LinkKind;
import no.priv.garshol.duke.Processor;
import no.priv.garshol.duke.LinkStatus;
import no.priv.garshol.duke.DataSource;
import no.priv.garshol.duke.Configuration;
import no.priv.garshol.duke.RecordIterator;
import no.priv.garshol.duke.InMemoryLinkDatabase;
import no.priv.garshol.duke.utils.LinkDatabaseUtils;
import no.priv.garshol.duke.matchers.MatchListener;
import no.priv.garshol.duke.matchers.TestFileListener;
import no.priv.garshol.duke.matchers.PrintMatchListener;

/**
 * The class that actually runs the genetic algorithm.
 */
public class GeneticAlgorithm {
  private Configuration config;
  private GeneticPopulation population;
  private Database database;
  private InMemoryLinkDatabase testdb;
  private double best; // best ever
  private boolean active; // true iff we are using active learning
  private Oracle oracle;

  private int generations;
  private int questions; // number of questions to ask per iteration
  
  public GeneticAlgorithm(Configuration config, String testfile)
    throws IOException {
    this.config = config;
    this.population = new GeneticPopulation(config);
    this.generations = 100;
    this.questions = 10;
    this.oracle = new ConsoleOracle();
    this.testdb = new InMemoryLinkDatabase();
    testdb.setDoInference(true);
    if (testfile != null)
      LinkDatabaseUtils.loadTestFile(testfile, testdb);
    else
      active = true;
  }

  /**
   * Actually runs the genetic algorithm.
   */
  public void run() {
    // first index up all records
    database = config.createDatabase(true);
    for (DataSource src : config.getDataSources()) {
      RecordIterator it = src.getRecords();
      while (it.hasNext())
        database.index(it.next());
    }
    database.commit();
    
    // make first, random population
    population.create();

    // run through the required number of generations
    for (int gen = 0; gen < generations; gen++) {
      System.out.println("===== GENERATION " + gen);
      evolve(gen);
    }
  }

  /**
   * Creates a new generation.
   * @param gen_no The number of the generation. The first is 0.
   */
  public void evolve(int gen_no) {
    // evaluate current generation
    List<GeneticConfiguration> pop = population.getConfigs();
    ExemplarsTracker tracker = null;
    if (active) {
      // the first time we try to find correct matches so that we're
      // guranteed the algorithm knows about *some* correct matches
      Scorer scorer = gen_no == 0 ?
        new FindCorrectScorer() : new DisagreementScorer();
      tracker = new ExemplarsTracker(config, scorer);
    }
    for (GeneticConfiguration cfg : pop) {
      double f = evaluate(cfg, tracker);
      System.out.println("  " + f);
      if (f > best) {
        System.out.println("\nNEW BEST!\n");
        best = f;
      }
    }

    population.sort();

    // compute some key statistics
    double fsum = 0.0;
    double lbest = 0.0;
    for (GeneticConfiguration cfg : pop) {
      fsum += cfg.getFNumber();
      if (cfg.getFNumber() > lbest)
        lbest = cfg.getFNumber();
    }
    System.out.println("BEST: " + lbest + " AVERAGE: " + (fsum / pop.size()));
    for (GeneticConfiguration cfg : population.getConfigs())
      System.out.print(cfg.getFNumber() + " ");
    System.out.println();

    // ask questions, if we're active
    if (active)
      askQuestions(tracker);
    
    // produce next generation
    int size = pop.size();
    List<GeneticConfiguration> nextgen = new ArrayList(size);
    for (GeneticConfiguration cfg : pop.subList(0, (int) (size * 0.02)))
      nextgen.add(new GeneticConfiguration(cfg));
    for (GeneticConfiguration cfg : pop.subList(0, (int) (size * 0.03)))
      nextgen.add(new GeneticConfiguration(cfg));
    for (GeneticConfiguration cfg : pop.subList(0, (int) (size * 0.25)))
      nextgen.add(new GeneticConfiguration(cfg));
    for (GeneticConfiguration cfg : pop.subList(0, (int) (size * 0.25)))
      nextgen.add(new GeneticConfiguration(cfg));
    for (GeneticConfiguration cfg : pop.subList((int) (size * 0.25), (int) (size * 0.7)))
      nextgen.add(new GeneticConfiguration(cfg));
    
    if (nextgen.size() > size)
      nextgen = nextgen.subList(0, size);

    for (GeneticConfiguration cfg : nextgen)
      if (Math.random() <= 0.75)
        cfg.mutate();
      else
        cfg.mateWith(population.pickRandomConfig());
    
    population.setNewGeneration(nextgen);
  }

  /**
   * Evaluates the given configuration, storing the score on the object.
   * @param config The configuration to evaluate.
   * @param listener A match listener to register on the processor. Can
   *                 be null.
   * @return The F-number of the configuration.
   */
  private double evaluate(GeneticConfiguration config,
                          MatchListener listener) {
    System.out.println(config);

    Configuration cconfig = config.getConfiguration();
    Processor proc = new Processor(cconfig, database);
    TestFileListener eval = new TestFileListener(testdb, cconfig, false,
                                                 proc, false, false);
    eval.setQuiet(true);
    eval.setPessimistic(!active); // active learning requires optimism to work
    proc.addMatchListener(eval);
    if (listener != null)
      proc.addMatchListener(listener);
    proc.linkRecords(cconfig.getDataSources());  // FIXME: record linkage mode

    config.setFNumber(eval.getFNumber());
    return eval.getFNumber();
  }

  public GeneticConfiguration getBestConfiguration() {
    return population.getBestConfiguration();
  }

  public GeneticPopulation getPopulation() {
    return population;
  }

  private void askQuestions(ExemplarsTracker tracker) {
    int count = 0;
    for (Pair pair : tracker.getExemplars()) {
      if (testdb.inferLink(pair.id1, pair.id2) != null)
        continue; // we already know the answer

      System.out.println();
      Record r1 = database.findRecordById(pair.id1);
      Record r2 = database.findRecordById(pair.id2);
      PrintMatchListener.prettyCompare(r1, r2, (double) pair.counter,
                                       "Possible match", 
                                       config.getProperties());
      
      LinkKind kind = oracle.getLinkKind(pair.id1, pair.id2);
      Link link = new Link(pair.id1, pair.id2, LinkStatus.ASSERTED, kind);
      testdb.assertLink(link);

      count++;
      if (count == questions)
        break;
    }
  }

  // this one tries to find correct matches
  static class FindCorrectScorer implements Scorer {
    public int computeScore(int count) {
      return count; // higher count -> higher score
    }
  }

  // this one tries to find the matches with the most information, by
  // picking the ones there is most disagreement on
  class DisagreementScorer implements Scorer {
    public int computeScore(int count) {
      int size = population.size();
      return (size - count) * (size - (size - count));
    }
  }
}