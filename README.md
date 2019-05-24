logtrix 
=======

This repository is a series of modifications to the existing logtrix code. See https://sbforge.org/display/NAS/Better+QA+With+Logtrix
for more discussion.



Examples
--------

### Building

```
   mvn -DskipTests clean package
```

### Parsing To The Ungrouped Format

```
   java -cp 'target/logtrix-0.2.0-SNAPSHOT.jar:target/lib/*' org.netpreserve.logtrix.CrawlSummary  crawl_log.log >crawl_log.old.json
```

### Parsing To The Grouped-by-seed Format

```
   java -cp 'target/logtrix-0.2.0-SNAPSHOT.jar:target/lib/*' org.netpreserve.logtrix.CrawlSummary -s crawl_log.log >crawl_log.new.json
```

### Starting a Webserver
```
cd resources/ui
python -m SimpleHTTPServer 
```

Then browse to http://localhost:8000 and select the ungrouped json file or http://localhost:8000/new.html and select the grouped file.
