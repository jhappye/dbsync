db-sync
=======

Syncronize two JDBC databases


Done
----
* Enable identity insert
* Disable constraints
* Get schema from DB
* Find only tables with primary key and partitionId columns
* Execute hash-query against source and destination DBs
* Detect row state as inserted, updated, deleted, or unchanged
* Get the actual row data for changed rows
* Bulk insert updated records
* Re-enable constraints when finished
* Sync everything from non-partitionId-having tables
* Delete
* Update

TODO
----
* Swing UI
* Store settings in config file
* Performance testing
* Refactor and test
* Generalize out partitionId - just use a map of keys & values (e.g. ParitionId='BD140B4B-FFCA-2E3A-5FA0-BCBA3E2678B2' from the command line)

Bugs
----
* Updates always happening even on when there are no changes
* Log table always gets 1999 rows inserted
* PartitionId filtering appears to be ignored 
