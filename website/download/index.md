---
layout: download
title: Download
permalink: /download/index.html
---

__Latest Release (Source Code)__  
The latest release of Apache Kylin™ can be downloaded from the ASF:

* [Apache Kylin v1.5.1](http://www.apache.org/dyn/closer.cgi/kylin/apache-kylin-1.5.1/)
* [Release Notes](http://kylin.apache.org/docs15/release_notes.html)
* Git Tag: [kylin-1.5.1](https://github.com/apache/kylin/tree/kylin-1.5.1)
* Git Commit: [aa98875c1b603e79b866b5e91bc3288e61a0b679](https://github.com/apache/kylin/commit/aa98875c1b603e79b866b5e91bc3288e61a0b679)

__Binary Package (for running on HBase 0.98/0.99)__
For convenience, there’s binary package also available: 

* [apache-kylin-1.5.1-bin.tar.gz](https://dist.apache.org/repos/dist/release/kylin/apache-kylin-1.5.1/apache-kylin-1.5.1-bin.tar.gz)
* [Installation Guide](http://kylin.apache.org/docs15/install)

__Special Binary Package (for running on HBase 1.1.3 or above)__
As there are more and more HBase 1.1 deployments, an binary snapshot build for HBase 1.1.3+ is provided; 
Note the requirement of HBase version 1.1.3 (or above). There is a known bug in HBase earlier versions about fuzzy key filter that will cause
missing rows or lesser aggregations in Kylin query result: [HBASE-14269](https://issues.apache.org/jira/browse/HBASE-14269)
Also, please aware this is not a formal release (rebasing latest changings on KYLIN 1.3.x branch every couple of weeks), and it is not fully tested:

* [apache-kylin-1.5.0-HBase1.1.3-bin.tar.gz](https://dist.apache.org/repos/dist/release/kylin/apache-kylin-1.5.0/apache-kylin-1.5.0-HBase1.1.3-bin.tar.gz)
* Git commit [dffbbf3bb4ff4414a751d5635974362513b36513](https://github.com/apache/kylin/commit/dffbbf3bb4ff4414a751d5635974362513b36513) 

If you're using HBase 1.0, we suggest you to upgrade to 1.1.3+ or downgrade to 0.98/0.99.

__Build Binary Package__
To build binary package from any version even latest development branch, please refer to this [guide](https://kylin.apache.org/development/howto_package.html)

__Previous Release__  
 Older releases can be found in the [archives](https://archive.apache.org/dist/kylin/).
    
__ODBC Driver__  
Kylin ODBC driver requires [Microsoft Visual C++ 2012 Redistributable](http://www.microsoft.com/en-us/download/details.aspx?id=30679) installed first. 
And Kylin ODBC Driver could be downloaded here: 

* [Kylin ODBC Driver v1.5](http://kylin.apache.org/download/KylinODBCDriver-1.5.zip) (recommended, compatible with all Kylin versions)
* [Kylin ODBC Driver v1.2](http://kylin.apache.org/download/KylinODBCDriver-1.2.zip)

