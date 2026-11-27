[![Apache Sling](https://sling.apache.org/res/logos/sling.png)](https://sling.apache.org)

&#32;[![Build Status](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-resourceresolver/job/master/badge/icon)](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-resourceresolver/job/master/)&#32;[![Test Status](https://img.shields.io/jenkins/tests.svg?jobUrl=https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-resourceresolver/job/master/)](https://ci-builds.apache.org/job/Sling/job/modules/job/sling-org-apache-sling-resourceresolver/job/master/test/?width=800&height=600)&#32;[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=apache_sling-org-apache-sling-resourceresolver&metric=coverage)](https://sonarcloud.io/dashboard?id=apache_sling-org-apache-sling-resourceresolver)&#32;[![Sonarcloud Status](https://sonarcloud.io/api/project_badges/measure?project=apache_sling-org-apache-sling-resourceresolver&metric=alert_status)](https://sonarcloud.io/dashboard?id=apache_sling-org-apache-sling-resourceresolver)&#32;[![JavaDoc](https://www.javadoc.io/badge/org.apache.sling/org.apache.sling.resourceresolver.svg)](https://www.javadoc.io/doc/org.apache.sling/org.apache.sling.resourceresolver)&#32;[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.apache.sling/org.apache.sling.resourceresolver/badge.svg)](https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.apache.sling%22%20a%3A%22org.apache.sling.resourceresolver%22) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

# Apache Sling Resource Resolver

This module is part of the [Apache Sling](https://sling.apache.org) project.

This bundle provides the Resource Resolver and Resource Resolver Factory. It aggregates
`ResourceProvider` OSGi services into a unified resource tree and handles URL mapping,
vanity paths, aliases, namespace mangling, and observation event routing.

## Requirements

* Java 17+
* Maven

## Build and test

```bash
# compile
mvn -q compile

# run all tests
mvn -q test

# build bundle (without tests)
mvn -q clean package -DskipTests

# full verification (tests + checks)
mvn verify

# code format checks
mvn spotless:check
mvn spotless:apply

# API baseline check
mvn bnd-baseline:baseline
```

## Project layout

```text
src/main/java/org/apache/sling/resourceresolver/impl/
  ResourceResolverFactoryActivator.java
  ResourceResolverFactoryImpl.java
  ResourceResolverImpl.java
  mapping/
  providers/
  observation/
  helper/
  params/
  legacy/
  console/

src/test/java/
src/test/resources/
```

## Configuration and documentation

The `/etc/map` mapping configuration is documented on the Sling website:
[Mappings for Resource Resolution](https://sling.apache.org/documentation/the-sling-engine/mappings-for-resource-resolution.html).

Additional background:

* [Apache Sling Documentation](https://sling.apache.org/documentation.html)
* [Apache Sling Contributing Guide](https://sling.apache.org/contributing.html)
