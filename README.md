# Overview

In this project will review  Realtime data warehouse loading with Apache Geode.  The data warehouse is kept up to date based on policy.

This project will be using Apache Geode's Asynchronous Event Listener (AEL).  There are many uses cases AEL can serve.   A very common use-case is to buffer writes to keep disk based databases in-sync with the rapid in-memory changes.

If that database is a Massively Parallel Data Warehouse like Greenplum we can take advantage of Geode's ability to partition data.   This will create multiple pathways to the data's final location.  If the parallelism isn't high enough to create the required throughput we can increase parallelism by using Geodes ability to create multiple dispatching threads.

# Architecture

To enable data loading of Greenplum we make use of a process called GPFDist.   GPFDist is responsible for writing  data files out from Greenplum Database segments.   GPFDist has several methods for accepting data.   

For this project we are going to use pipes.   Pipes allow the sending process to keep data off of disk and limits the text processing around the transport.

Flow of data: As Geode is reacting to data requests it is capturing what has changed and is enqueueing those changes based on the policy the architect as chosen.   Geode will trigger the AEL based on policy.   That AEL then signals the GPFDist process to start listening on a pipe.   Geode then writes the contents of the queue to the pipe.  As GPFDist reads the data off of the pipe it routing the data to the correct Greenplum segment based on a policy setup in Greenplum.

![Data Pipeline](/images/DataFlowDiagram.gif)
