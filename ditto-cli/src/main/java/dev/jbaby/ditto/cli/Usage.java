package dev.jbaby.ditto.cli;

/**
 * Help text of the CLI.
 */
final class Usage {

    static final String TEXT = """
            Usage: java -jar ditto-cli.jar [compare] --collection=<collection> [options]
                   java -jar ditto-cli.jar [compare] --baseline=<collection> --candidate=<collection> [options]
                   java -jar ditto-cli.jar report [--id=<id> | --label=<key>=<value> | --collection=<collection>]
                   java -jar ditto-cli.jar copy-reports [--report-dir=<dir>]
                   java -jar ditto-cli.jar generate [options]
            OpenSearch: add --opensearch=<url>; collections are then indices (--index works as --collection)

            Connection
              --uri=<mongodb uri>           default mongodb://localhost:27017
              --db=<database>               default database, default "test"
              --opensearch=<url,...>        compare OpenSearch indices instead, e.g. http://localhost:9200
              --opensearch-user=<user>      basic authentication; password from DITTO_OPENSEARCH_PASSWORD

            compare (prints the report as JSON on stdout; exit code 0 GREEN, 1 YELLOW, 2 RED, 3 error)
              --collection=<collection>     compares <collection>_backup (baseline) with <collection> (candidate)
              --baseline=<collection>       reference collection, e.g. the backup
              --candidate=<collection>      collection to judge
              --baseline-db=<database>      database of the baseline if not --db
              --candidate-db=<database>     database of the candidate if not --db
              --key=<field>                 key field, default _id; OpenSearch: a keyword or integer field,
                                            or a text field with a keyword subfield
              --ignore=<path,...>           paths to ignore, e.g. meta.syncedAt,items[].etag
              --ordered=<path,...>          arrays whose element order matters
              --wildcard=<path,...>         maps with dynamic keys, e.g. attributes.*
              --expected=<path,...>         paths that are supposed to change (prices, counters); reported but
                                            not counted against the verdict
              --redact=<path,...>           paths whose values are shown as *** in value examples
              --mode=auto|full|sample       default auto: full scan up to 5,000,000 documents per side, else
                                            a sample (--ditto.full-scan-limit, --ditto.sample.size)
              --sample-size=<n>             sample size (implies --mode=sample)
              --matched-only                compare only documents whose key exists on both sides, e.g. when
                                            one side holds just a part of the data (test environment)
              --null-equals-missing         treat null fields like missing fields
              --mixed-key-types=reject|compare
              --verdict-basis=conservative|point   which value of estimated rates the verdict uses
              --label=<key>=<value>         label stored with the report, e.g. --label=batchJobId=4711;
                                            repeatable
              --persist                     store the report as a file in --report-dir (ditto.persistence.*;
                                            --ditto.persistence.storage=database: in the compared database)
              --report-dir=<dir>            directory of the stored reports, default ./ditto-reports
              --out=<file>                  also write the report to a file
              --html=<file>                 also write the report as a self-contained HTML page
              --ditto.<property>=...        any library property, e.g.
                                            --ditto.thresholds.key-similarity.green=0.995

            report (prints a stored report as JSON; exit code from its verdict, 3 if none matches)
              --id=<id>                     the report with this id
              --label=<key>=<value>         the newest report with these labels, e.g. --label=batchJobId=4711
              --collection=<collection>     the newest report for this candidate (also --candidate, --candidate-db)
                                            without a selection: the newest report
              --out=<file>, --html=<file>   also write the report as JSON or as a self-contained HTML page
              reads the report files in --report-dir (default ./ditto-reports), or with
              --ditto.persistence.storage=database ditto.persistence.database / .collection (default
              comparison_reports; with --opensearch the index of that name); changes nothing

            copy-reports (copies the reports stored in the database into --report-dir)
              reads ditto.persistence.database / .collection (default comparison_reports; with --opensearch
              the index of that name) and changes nothing there; writes a file per report into --report-dir
              (default ./ditto-reports), e.g. to keep the history when switching to report files

            generate (writes a baseline collection, or index, and a modified copy)
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
              In OpenSearch the ObjectId is the document id, dates are ISO strings, and only sku is indexed.
            """;

    private Usage() {
    }
}
