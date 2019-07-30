[<img src="https://sling.apache.org/res/logos/sling.png"/>](https://sling.apache.org)

 [![Build Status](https://builds.apache.org/buildStatus/icon?job=Sling/sling-org-apache-sling-resourceresolver/master)](https://builds.apache.org/job/Sling/job/sling-org-apache-sling-resourceresolver/job/master) [![Test Status](https://img.shields.io/jenkins/t/https/builds.apache.org/job/Sling/job/sling-org-apache-sling-resourceresolver/job/master.svg)](https://builds.apache.org/job/Sling/job/sling-org-apache-sling-resourceresolver/job/master/test_results_analyzer/) [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.apache.sling/org.apache.sling.resourceresolver/badge.svg)](https://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.apache.sling%22%20a%3A%22org.apache.sling.resourceresolver%22) [![JavaDocs](https://www.javadoc.io/badge/org.apache.sling/org.apache.sling.resourceresolver.svg)](https://www.javadoc.io/doc/org.apache.sling/org.apache.sling.resourceresolver) [![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

# Apache Sling Resource Resolver

This module is part of the [Apache Sling](https://sling.apache.org) project.

This bundle provides the Resource Resolver and Resource Resolver Factory

## ETC Map String Interpolation

Setting up ETC Mappings (/etc/map) for different instances like dev, stage,
qa and production was time consuming and error prone due to copy-n-paste
errors. 
As a new feature Sling now supports String Interpolation in the /etc/map.
With it it is possible to create a single set of etc-mapping and then adjust
the actual values of an instance by an OSGi configuration.
By default a variable name is enclosed in **${}** with a **$** as escape
character and no in-variable-substitution. All of that is configurable
together with the actual value map.

### Setup

The Substitution Configuration can be found in the OSGi Configuration
as **Apache Sling String Interpolation Provider**. The property **Placeholder
Values** takes a list of **key=value** entries where each of them map a
variable with its actual value.
In our little introduction we add an entry of
**phv.default.host.name=localhost**. Save the configuration for now.
Before going on make sure that you know Mapping Location configuration
in the OSGi configuration of **Apache Sling Resource Resolver Factory**.
Now to to **composum** and go to that node. If it does not exist then create
one. The mapping should look like this:
* etc
    * map
        * http
            * ${phv.fq.host.name}.8080
            
Opening the page **http://localhost:8080/starter/index.html** should
work just fine.

### Testing

Now got back to the String Interpolation configuration and change the value
to **qa.author.acme.com** and save it.

For local testing open your **hosts** file (/etc/hosts on Unix) and add a
line like this:
```
127.0.0.1 qa.author.acme.com
```
save it and test with `ping qa.author.acme.com` to make sure the name
resolves.
Now you should be able to open the same page with:
**http://qa.author.acme.com/starter/index.html**.

Now do the same with **phv.fq.host.name=staging.author.acme.com**.

The String Interpolation works with any part of the etc-map tree.
 
 

