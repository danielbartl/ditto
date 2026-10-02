package dev.jbaby.ditto.cli;

/**
 * Help text of the CLI.
 */
final class Usage {

    static final String TEXT = """
            Usage: java -jar comparator-cli.jar [compare] --baseline=<collection> --candidate=<collection> [options]
                   java -jar comparator-cli.jar generate [options]

            Connection
              --uri=<mongodb uri>           default mongodb://localhost:27017
              --db=<database>               default database, default "test"

            compare (prints the report as JSON on stdout; exit code 0 GREEN, 1 YELLOW, 2 RED, 3 error)
              --baseline=<collection>       reference collection, e.g. the backup (required)
              --candidate=<collection>      collection to judge (required)
              --baseline-db=<database>      database of the baseline if not --db
              --candidate-db=<database>     database of the candidate if not --db
              --key=<field>                 key field, default _id
              --ignore=<path,...>           paths to ignore, e.g. meta.syncedAt,items[].etag
              --ordered=<path,...>          arrays whose element order matters
              --wildcard=<path,...>         maps with dynamic keys, e.g. attributes.*
              --mode=full|sample            default full
              --sample-size=<n>             sample size (implies --mode=sample)
              --null-equals-missing         treat null fields like missing fields
              --mixed-key-types=reject|compare
              --verdict-basis=conservative|point   which value of estimated rates the verdict uses
              --persist                     store the report (comparator.persistence.*)
              --out=<file>                  also write the report to a file
              --comparator.<property>=...   any library property, e.g.
                                            --comparator.thresholds.key-similarity.green=0.995

            generate (writes a baseline collection and a modified copy)
              --baseline=<collection>       default demo_backup
              --candidate=<collection>      default demo
              --docs=<n>                    documents in the baseline, default 10000
              --seed=<n>                    random seed, default 42
              --touch-sync=true|false       refresh meta.syncedAt in every candidate document, default true
              --changes=<spec,...>          changes applied to the candidate, each <kind>[:<path>]:<fraction>
                  modify:<path>:<f>         change the value (numbers +1, strings suffixed, booleans flipped, ...)
                  drop-field:<path>:<f>     remove the field
                  add-field:<path>:<f>      add a new string field
                  set-null:<path>:<f>       set the field to null
                  int-to-double:<path>:<f>  same value, double instead of int
                  to-string:<path>:<f>      same value as a string
                  shuffle-arrays:<f>        shuffle all arrays (content-neutral unless the path is --ordered)
                  delete-docs:<f>           remove documents
                  add-docs:<f>              add new documents (fraction of --docs)
                example: --changes=modify:price:0.05,drop-field:legacyCode:1,shuffle-arrays:1,delete-docs:0.01
              Generated fields: _id (ObjectId), sku, name, price (decimal), qty (int), active, createdAt,
              category{id,name,path[]}, tags[], attributes{<dynamic>}, variants[{sku,price,stock,dims{w,h}}],
              history[] (ordered events), legacyCode, note (sometimes null), meta{syncedAt,source,version}
            """;

    private Usage() {
    }
}
