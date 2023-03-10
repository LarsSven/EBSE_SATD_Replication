---
layout: global
displayTitle: Spark SQL, DataFrames and Datasets Guide
title: Spark SQL and DataFrames
---

* This will become a table of contents (this text will be scraped).
{:toc}

# Overview

Spark SQL is a Spark module for structured data processing. Unlike the basic Spark RDD API, the interfaces provided
by Spark SQL provide Spark with more information about the structure of both the data and the computation being performed. Internally,
Spark SQL uses this extra information to perform extra optimizations. There are several ways to
interact with Spark SQL including SQL and the Dataset API. When computing a result
the same execution engine is used, independent of which API/language you are using to express the
computation. This unification means that developers can easily switch back and forth between
different APIs based on which provides the most natural way to express a given transformation.

All of the examples on this page use sample data included in the Spark distribution and can be run in
the `spark-shell`, `pyspark` shell, or `sparkR` shell.

## SQL

One use of Spark SQL is to execute SQL queries.
Spark SQL can also be used to read data from an existing Hive installation. For more on how to
configure this feature, please refer to the [Hive Tables](#hive-tables) section. When running
SQL from within another programming language the results will be returned as a [Dataset/DataFrame](#datasets-and-dataframes).
You can also interact with the SQL interface using the [command-line](#running-the-spark-sql-cli)
or over [JDBC/ODBC](#running-the-thrift-jdbcodbc-server).

## Datasets and DataFrames

A Dataset is a distributed collection of data.
Dataset is a new interface added in Spark 1.6 that provides the benefits of RDDs (strong
typing, ability to use powerful lambda functions) with the benefits of Spark SQL's optimized
execution engine. A Dataset can be [constructed](#creating-datasets) from JVM objects and then
manipulated using functional transformations (`map`, `flatMap`, `filter`, etc.).
The Dataset API is available in [Scala][scala-datasets] and
[Java][java-datasets]. Python does not have the support for the Dataset API. But due to Python's dynamic nature,
many of the benefits of the Dataset API are already available (i.e. you can access the field of a row by name naturally
`row.columnName`). The case for R is similar.

A DataFrame is a *Dataset* organized into named columns. It is conceptually
equivalent to a table in a relational database or a data frame in R/Python, but with richer
optimizations under the hood. DataFrames can be constructed from a wide array of [sources](#data-sources) such
as: structured data files, tables in Hive, external databases, or existing RDDs.
The DataFrame API is available in Scala,
Java, [Python](api/python/pyspark.sql.html#pyspark.sql.DataFrame), and [R](api/R/index.html).
In Scala and Java, a DataFrame is represented by a Dataset of `Row`s.
In [the Scala API][scala-datasets], `DataFrame` is simply a type alias of `Dataset[Row]`.
While, in [Java API][java-datasets], users need to use `Dataset<Row>` to represent a `DataFrame`.

[scala-datasets]: api/scala/index.html#org.apache.spark.sql.Dataset
[java-datasets]: api/java/index.html?org/apache/spark/sql/Dataset.html

Throughout this document, we will often refer to Scala/Java Datasets of `Row`s as DataFrames.

# Getting Started

## Starting Point: SparkSession

<div class="codetabs">
<div data-lang="scala"  markdown="1">

The entry point into all functionality in Spark is the [`SparkSession`](api/scala/index.html#org.apache.spark.sql.SparkSession) class. To create a basic `SparkSession`, just use `SparkSession.builder()`:

{% include_example init_session scala/org/apache/spark/examples/sql/SparkSQLExample.scala %}
</div>

<div data-lang="java" markdown="1">

The entry point into all functionality in Spark is the [`SparkSession`](api/java/index.html#org.apache.spark.sql.SparkSession) class. To create a basic `SparkSession`, just use `SparkSession.builder()`:

{% include_example init_session java/org/apache/spark/examples/sql/JavaSparkSQLExample.java %}
</div>

<div data-lang="python"  markdown="1">

The entry point into all functionality in Spark is the [`SparkSession`](api/python/pyspark.sql.html#pyspark.sql.SparkSession) class. To create a basic `SparkSession`, just use `SparkSession.builder`:

{% include_example init_session python/sql/basic.py %}
</div>

<div data-lang="r"  markdown="1">

The entry point into all functionality in Spark is the [`SparkSession`](api/R/sparkR.session.html) class. To initialize a basic `SparkSession`, just call `sparkR.session()`:

{% include_example init_session r/RSparkSQLExample.R %}

Note that when invoked for the first time, `sparkR.session()` initializes a global `SparkSession` singleton instance, and always returns a reference to this instance for successive invocations. In this way, users only need to initialize the `SparkSession` once, then SparkR functions like `read.df` will be able to access this global instance implicitly, and users don't need to pass the `SparkSession` instance around.
</div>
</div>

`SparkSession` in Spark 2.0 provides builtin support for Hive features including the ability to
write queries using HiveQL, access to Hive UDFs, and the ability to read data from Hive tables.
To use these features, you do not need to have an existing Hive setup.

## Creating DataFrames

<div class="codetabs">
<div data-lang="scala"  markdown="1">
With a `SparkSession`, applications can create DataFrames from an [existing `RDD`](#interoperating-with-rdds),
from a Hive table, or from [Spark data sources](#data-sources).

As an example, the following creates a DataFrame based on the content of a JSON file:

{% include_example create_df scala/org/apache/spark/examples/sql/SparkSQLExample.scala %}
</div>

<div data-lang="java" markdown="1">
With a `SparkSession`, applications can create DataFrames from an [existing `RDD`](#interoperating-with-rdds),
from a Hive table, or from [Spark data sources](#data-sources).

As an example, the following creates a DataFrame based on the content of a JSON file:

{% include_example create_df java/org/apache/spark/examples/sql/JavaSparkSQLExample.java %}
</div>

<div data-lang="python"  markdown="1">
With a `SparkSession`, applications can create DataFrames from an [existing `RDD`](#interoperating-with-rdds),
from a Hive table, or from [Spark data sources](#data-sources).

As an example, the following creates a DataFrame based on the content of a JSON file:

{% include_example create_df python/sql/basic.py %}
</div>

<div data-lang="r"  markdown="1">
With a `SparkSession`, applications can create DataFrames from a local R data.frame,
from a Hive table, or from [Spark data sources](#data-sources).

As an example, the following creates a DataFrame based on the content of a JSON file:

{% include_example create_df r/RSparkSQLExample.R %}

</div>
</div>


## Untyped Dataset Operations (aka DataFrame Operations)

DataFrames provide a domain-specific language for structured data manipulation in [Scala](api/scala/index.html#org.apache.spark.sql.Dataset), [Java](api/java/index.html?org/apache/spark/sql/Dataset.html), [Python](api/python/pyspark.sql.html#pyspark.sql.DataFrame) and [R](api/R/SparkDataFrame.html).

As mentioned above, in Spark 2.0, DataFrames are just Dataset of `Row`s in Scala and Java API. These operations are also referred as "untyped transformations" in contrast to "typed transformations" come with strongly typed Scala/Java Datasets.

Here we include some basic examples of structured data processing using Datasets:

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% include_example untyped_ops scala/org/apache/spark/examples/sql/SparkSQLExample.scala %}

For a complete list of the types of operations that can be performed on a Dataset refer to the [API Documentation](api/scala/index.html#org.apache.spark.sql.Dataset).

In addition to simple column references and expressions, Datasets also have a rich library of functions including string manipulation, date arithmetic, common math operations and more. The complete list is available in the [DataFrame Function Reference](api/scala/index.html#org.apache.spark.sql.functions$).
</div>

<div data-lang="java" markdown="1">

{% include_example untyped_ops java/org/apache/spark/examples/sql/JavaSparkSQLExample.java %}

For a complete list of the types of operations that can be performed on a Dataset refer to the [API Documentation](api/java/org/apache/spark/sql/Dataset.html).

In addition to simple column references and expressions, Datasets also have a rich library of functions including string manipulation, date arithmetic, common math operations and more. The complete list is available in the [DataFrame Function Reference](api/java/org/apache/spark/sql/functions.html).
</div>

<div data-lang="python"  markdown="1">
In Python, it's possible to access a DataFrame's columns either by attribute
(`df.age`) or by indexing (`df['age']`). While the former is convenient for
interactive data exploration, users are highly encouraged to use the
latter form, which is future proof and won't break with column names that
are also attributes on the DataFrame class.

{% include_example untyped_ops python/sql/basic.py %}
For a complete list of the types of operations that can be performed on a DataFrame refer to the [API Documentation](api/python/pyspark.sql.html#pyspark.sql.DataFrame).

In addition to simple column references and expressions, DataFrames also have a rich library of functions including string manipulation, date arithmetic, common math operations and more. The complete list is available in the [DataFrame Function Reference](api/python/pyspark.sql.html#module-pyspark.sql.functions).

</div>

<div data-lang="r"  markdown="1">

{% include_example untyped_ops r/RSparkSQLExample.R %}

For a complete list of the types of operations that can be performed on a DataFrame refer to the [API Documentation](api/R/index.html).

In addition to simple column references and expressions, DataFrames also have a rich library of functions including string manipulation, date arithmetic, common math operations and more. The complete list is available in the [DataFrame Function Reference](api/R/SparkDataFrame.html).

</div>

</div>

## Running SQL Queries Programmatically

<div class="codetabs">
<div data-lang="scala"  markdown="1">
The `sql` function on a `SparkSession` enables applications to run SQL queries programmatically and returns the result as a `DataFrame`.

{% include_example run_sql scala/org/apache/spark/examples/sql/SparkSQLExample.scala %}
</div>

<div data-lang="java" markdown="1">
The `sql` function on a `SparkSession` enables applications to run SQL queries programmatically and returns the result as a `Dataset<Row>`.

{% include_example run_sql java/org/apache/spark/examples/sql/JavaSparkSQLExample.java %}
</div>

<div data-lang="python"  markdown="1">
The `sql` function on a `SparkSession` enables applications to run SQL queries programmatically and returns the result as a `DataFrame`.

{% include_example run_sql python/sql/basic.py %}
</div>

<div data-lang="r"  markdown="1">
The `sql` function enables applications to run SQL queries programmatically and returns the result as a `SparkDataFrame`.

{% include_example run_sql r/RSparkSQLExample.R %}

</div>
</div>


## Global Temporary View

Temporary views in Spark SQL are session-scoped and will disappear if the session that creates it
terminates. If you want to have a temporary view that is shared among all sessions and keep alive
until the Spark application terminates, you can create a global temporary view. Global temporary
view is tied to a system preserved database `global_temp`, and we must use the qualified name to
refer it, e.g. `SELECT * FROM global_temp.view1`.

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% include_example global_temp_view scala/org/apache/spark/examples/sql/SparkSQLExample.scala %}
</div>

<div data-lang="java" markdown="1">
{% include_example global_temp_view java/org/apache/spark/examples/sql/JavaSparkSQLExample.java %}
</div>

<div data-lang="python"  markdown="1">
{% include_example global_temp_view python/sql/basic.py %}
</div>

<div data-lang="sql"  markdown="1">

{% highlight sql %}

CREATE GLOBAL TEMPORARY VIEW temp_view AS SELECT a + 1, b * 2 FROM tbl

SELECT * FROM global_temp.temp_view

{% endhighlight %}

</div>
</div>


## Creating Datasets

Datasets are similar to RDDs, however, instead of using Java serialization or Kryo they use
a specialized [Encoder](api/scala/index.html#org.apache.spark.sql.Encoder) to serialize the objects
for processing or transmitting over the network. While both encoders and standard serialization are
responsible for turning an object into bytes, encoders are code generated dynamically and use a format
that allows Spark to perform many operations like filtering, sorting and hashing without deserializing
the bytes back into an object.

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% include_example create_ds scala/org/apache/spark/examples/sql/SparkSQLExample.scala %}
</div>

<div data-lang="java" markdown="1">
{% include_example create_ds java/org/apache/spark/examples/sql/JavaSparkSQLExample.java %}
</div>
</div>

## Interoperating with RDDs

Spark SQL supports two different methods for converting existing RDDs into Datasets. The first
method uses reflection to infer the schema of an RDD that contains specific types of objects. This
reflection-based approach leads to more concise code and works well when you already know the schema
while writing your Spark application.

The second method for creating Datasets is through a programmatic interface that allows you to
construct a schema and then apply it to an existing RDD. While this method is more verbose, it allows
you to construct Datasets when the columns and their types are not known until runtime.

### Inferring the Schema Using Reflection
<div class="codetabs">

<div data-lang="scala"  markdown="1">

The Scala interface for Spark SQL supports automatically converting an RDD containing case classes
to a DataFrame. The case class
defines the schema of the table. The names of the arguments to the case class are read using
reflection and become the names of the columns. Case classes can also be nested or contain complex
types such as `Seq`s or `Array`s. This RDD can be implicitly converted to a DataFrame and then be
registered as a table. Tables can be used in subsequent SQL statements.

{% include_example schema_inferring scala/org/apache/spark/examples/sql/SparkSQLExample.scala %}
</div>

<div data-lang="java"  markdown="1">

Spark SQL supports automatically converting an RDD of
[JavaBeans](http://stackoverflow.com/questions/3295496/what-is-a-javabean-exactly) into a DataFrame.
The `BeanInfo`, obtained using reflection, defines the schema of the table. Currently, Spark SQL
does not support JavaBeans that contain `Map` field(s). Nested JavaBeans and `List` or `Array`
fields are supported though. You can create a JavaBean by creating a class that implements
Serializable and has getters and setters for all of its fields.

{% include_example schema_inferring java/org/apache/spark/examples/sql/JavaSparkSQLExample.java %}
</div>

<div data-lang="python"  markdown="1">

Spark SQL can convert an RDD of Row objects to a DataFrame, inferring the datatypes. Rows are constructed by passing a list of
key/value pairs as kwargs to the Row class. The keys of this list define the column names of the table,
and the types are inferred by sampling the whole dataset, similar to the inference that is performed on JSON files.

{% include_example schema_inferring python/sql/basic.py %}
</div>

</div>

### Programmatically Specifying the Schema

<div class="codetabs">

<div data-lang="scala"  markdown="1">

When case classes cannot be defined ahead of time (for example,
the structure of records is encoded in a string, or a text dataset will be parsed
and fields will be projected differently for different users),
a `DataFrame` can be created programmatically with three steps.

1. Create an RDD of `Row`s from the original RDD;
2. Create the schema represented by a `StructType` matching the structure of
`Row`s in the RDD created in Step 1.
3. Apply the schema to the RDD of `Row`s via `createDataFrame` method provided
by `SparkSession`.

For example:

{% include_example programmatic_schema scala/org/apache/spark/examples/sql/SparkSQLExample.scala %}
</div>

<div data-lang="java"  markdown="1">

When JavaBean classes cannot be defined ahead of time (for example,
the structure of records is encoded in a string, or a text dataset will be parsed and
fields will be projected differently for different users),
a `Dataset<Row>` can be created programmatically with three steps.

1. Create an RDD of `Row`s from the original RDD;
2. Create the schema represented by a `StructType` matching the structure of
`Row`s in the RDD created in Step 1.
3. Apply the schema to the RDD of `Row`s via `createDataFrame` method provided
by `SparkSession`.

For example:

{% include_example programmatic_schema java/org/apache/spark/examples/sql/JavaSparkSQLExample.java %}
</div>

<div data-lang="python"  markdown="1">

When a dictionary of kwargs cannot be defined ahead of time (for example,
the structure of records is encoded in a string, or a text dataset will be parsed and
fields will be projected differently for different users),
a `DataFrame` can be created programmatically with three steps.

1. Create an RDD of tuples or lists from the original RDD;
2. Create the schema represented by a `StructType` matching the structure of
tuples or lists in the RDD created in the step 1.
3. Apply the schema to the RDD via `createDataFrame` method provided by `SparkSession`.

For example:

{% include_example programmatic_schema python/sql/basic.py %}
</div>

</div>

## Aggregations

The [built-in DataFrames functions](api/scala/index.html#org.apache.spark.sql.functions$) provide common
aggregations such as `count()`, `countDistinct()`, `avg()`, `max()`, `min()`, etc.
While those functions are designed for DataFrames, Spark SQL also has type-safe versions for some of them in
[Scala](api/scala/index.html#org.apache.spark.sql.expressions.scalalang.typed$) and
[Java](api/java/org/apache/spark/sql/expressions/javalang/typed.html) to work with strongly typed Datasets.
Moreover, users are not limited to the predefined aggregate functions and can create their own.

### Untyped User-Defined Aggregate Functions
Users have to extend the [UserDefinedAggregateFunction](api/scala/index.html#org.apache.spark.sql.expressions.UserDefinedAggregateFunction)
abstract class to implement a custom untyped aggregate function. For example, a user-defined average
can look like:

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% include_example untyped_custom_aggregation scala/org/apache/spark/examples/sql/UserDefinedUntypedAggregation.scala%}
</div>
<div data-lang="java"  markdown="1">
{% include_example untyped_custom_aggregation java/org/apache/spark/examples/sql/JavaUserDefinedUntypedAggregation.java%}
</div>
</div>

### Type-Safe User-Defined Aggregate Functions

User-defined aggregations for strongly typed Datasets revolve around the [Aggregator](api/scala/index.html#org.apache.spark.sql.expressions.Aggregator) abstract class.
For example, a type-safe user-defined average can look like:

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% include_example typed_custom_aggregation scala/org/apache/spark/examples/sql/UserDefinedTypedAggregation.scala%}
</div>
<div data-lang="java"  markdown="1">
{% include_example typed_custom_aggregation java/org/apache/spark/examples/sql/JavaUserDefinedTypedAggregation.java%}
</div>
</div>

# Data Sources

Spark SQL supports operating on a variety of data sources through the DataFrame interface.
A DataFrame can be operated on using relational transformations and can also be used to create a temporary view.
Registering a DataFrame as a temporary view allows you to run SQL queries over its data. This section
describes the general methods for loading and saving data using the Spark Data Sources and then
goes into specific options that are available for the built-in data sources.

## Generic Load/Save Functions

In the simplest form, the default data source (`parquet` unless otherwise configured by
`spark.sql.sources.default`) will be used for all operations.

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% include_example generic_load_save_functions scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example generic_load_save_functions java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">

{% include_example generic_load_save_functions python/sql/datasource.py %}
</div>

<div data-lang="r"  markdown="1">

{% include_example generic_load_save_functions r/RSparkSQLExample.R %}

</div>
</div>

### Manually Specifying Options

You can also manually specify the data source that will be used along with any extra options
that you would like to pass to the data source. Data sources are specified by their fully qualified
name (i.e., `org.apache.spark.sql.parquet`), but for built-in sources you can also use their short
names (`json`, `parquet`, `jdbc`, `orc`, `libsvm`, `csv`, `text`). DataFrames loaded from any data
source type can be converted into other types using this syntax.

To load a JSON file you can use:

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% include_example manual_load_options scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example manual_load_options java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">
{% include_example manual_load_options python/sql/datasource.py %}
</div>

<div data-lang="r"  markdown="1">
{% include_example manual_load_options r/RSparkSQLExample.R %}
</div>
</div>

To load a CSV file you can use:

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% include_example manual_load_options_csv scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example manual_load_options_csv java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">
{% include_example manual_load_options_csv python/sql/datasource.py %}
</div>

<div data-lang="r"  markdown="1">
{% include_example manual_load_options_csv r/RSparkSQLExample.R %}

</div>
</div>

### Run SQL on files directly

Instead of using read API to load a file into DataFrame and query it, you can also query that
file directly with SQL.

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% include_example direct_sql scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example direct_sql java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">
{% include_example direct_sql python/sql/datasource.py %}
</div>

<div data-lang="r"  markdown="1">
{% include_example direct_sql r/RSparkSQLExample.R %}

</div>
</div>

### Save Modes

Save operations can optionally take a `SaveMode`, that specifies how to handle existing data if
present. It is important to realize that these save modes do not utilize any locking and are not
atomic. Additionally, when performing an `Overwrite`, the data will be deleted before writing out the
new data.

<table class="table">
<tr><th>Scala/Java</th><th>Any Language</th><th>Meaning</th></tr>
<tr>
  <td><code>SaveMode.ErrorIfExists</code> (default)</td>
  <td><code>"error" or "errorifexists"</code> (default)</td>
  <td>
    When saving a DataFrame to a data source, if data already exists,
    an exception is expected to be thrown.
  </td>
</tr>
<tr>
  <td><code>SaveMode.Append</code></td>
  <td><code>"append"</code></td>
  <td>
    When saving a DataFrame to a data source, if data/table already exists,
    contents of the DataFrame are expected to be appended to existing data.
  </td>
</tr>
<tr>
  <td><code>SaveMode.Overwrite</code></td>
  <td><code>"overwrite"</code></td>
  <td>
    Overwrite mode means that when saving a DataFrame to a data source,
    if data/table already exists, existing data is expected to be overwritten by the contents of
    the DataFrame.
  </td>
</tr>
<tr>
  <td><code>SaveMode.Ignore</code></td>
  <td><code>"ignore"</code></td>
  <td>
    Ignore mode means that when saving a DataFrame to a data source, if data already exists,
    the save operation is expected to not save the contents of the DataFrame and to not
    change the existing data. This is similar to a <code>CREATE TABLE IF NOT EXISTS</code> in SQL.
  </td>
</tr>
</table>

### Saving to Persistent Tables

`DataFrames` can also be saved as persistent tables into Hive metastore using the `saveAsTable`
command. Notice that an existing Hive deployment is not necessary to use this feature. Spark will create a
default local Hive metastore (using Derby) for you. Unlike the `createOrReplaceTempView` command,
`saveAsTable` will materialize the contents of the DataFrame and create a pointer to the data in the
Hive metastore. Persistent tables will still exist even after your Spark program has restarted, as
long as you maintain your connection to the same metastore. A DataFrame for a persistent table can
be created by calling the `table` method on a `SparkSession` with the name of the table.

For file-based data source, e.g. text, parquet, json, etc. you can specify a custom table path via the
`path` option, e.g. `df.write.option("path", "/some/path").saveAsTable("t")`. When the table is dropped,
the custom table path will not be removed and the table data is still there. If no custom table path is
specified, Spark will write data to a default table path under the warehouse directory. When the table is
dropped, the default table path will be removed too.

Starting from Spark 2.1, persistent datasource tables have per-partition metadata stored in the Hive metastore. This brings several benefits:

- Since the metastore can return only necessary partitions for a query, discovering all the partitions on the first query to the table is no longer needed.
- Hive DDLs such as `ALTER TABLE PARTITION ... SET LOCATION` are now available for tables created with the Datasource API.

Note that partition information is not gathered by default when creating external datasource tables (those with a `path` option). To sync the partition information in the metastore, you can invoke `MSCK REPAIR TABLE`.

### Bucketing, Sorting and Partitioning

For file-based data source, it is also possible to bucket and sort or partition the output.
Bucketing and sorting are applicable only to persistent tables:

<div class="codetabs">

<div data-lang="scala"  markdown="1">
{% include_example write_sorting_and_bucketing scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example write_sorting_and_bucketing java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">
{% include_example write_sorting_and_bucketing python/sql/datasource.py %}
</div>

<div data-lang="sql"  markdown="1">

{% highlight sql %}

CREATE TABLE users_bucketed_by_name(
  name STRING,
  favorite_color STRING,
  favorite_numbers array<integer>
) USING parquet
CLUSTERED BY(name) INTO 42 BUCKETS;

{% endhighlight %}

</div>

</div>

while partitioning can be used with both `save` and `saveAsTable` when using the Dataset APIs.


<div class="codetabs">

<div data-lang="scala"  markdown="1">
{% include_example write_partitioning scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example write_partitioning java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">
{% include_example write_partitioning python/sql/datasource.py %}
</div>

<div data-lang="sql"  markdown="1">

{% highlight sql %}

CREATE TABLE users_by_favorite_color(
  name STRING,
  favorite_color STRING,
  favorite_numbers array<integer>
) USING csv PARTITIONED BY(favorite_color);

{% endhighlight %}

</div>

</div>

It is possible to use both partitioning and bucketing for a single table:

<div class="codetabs">

<div data-lang="scala"  markdown="1">
{% include_example write_partition_and_bucket scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example write_partition_and_bucket java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">
{% include_example write_partition_and_bucket python/sql/datasource.py %}
</div>

<div data-lang="sql"  markdown="1">

{% highlight sql %}

CREATE TABLE users_bucketed_and_partitioned(
  name STRING,
  favorite_color STRING,
  favorite_numbers array<integer>
) USING parquet
PARTITIONED BY (favorite_color)
CLUSTERED BY(name) SORTED BY (favorite_numbers) INTO 42 BUCKETS;

{% endhighlight %}

</div>

</div>

`partitionBy` creates a directory structure as described in the [Partition Discovery](#partition-discovery) section.
Thus, it has limited applicability to columns with high cardinality. In contrast
 `bucketBy` distributes
data across a fixed number of buckets and can be used when a number of unique values is unbounded.

## Parquet Files

[Parquet](http://parquet.io) is a columnar format that is supported by many other data processing systems.
Spark SQL provides support for both reading and writing Parquet files that automatically preserves the schema
of the original data. When writing Parquet files, all columns are automatically converted to be nullable for
compatibility reasons.

### Loading Data Programmatically

Using the data from the above example:

<div class="codetabs">

<div data-lang="scala"  markdown="1">
{% include_example basic_parquet_example scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example basic_parquet_example java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">

{% include_example basic_parquet_example python/sql/datasource.py %}
</div>

<div data-lang="r"  markdown="1">

{% include_example basic_parquet_example r/RSparkSQLExample.R %}

</div>

<div data-lang="sql"  markdown="1">

{% highlight sql %}

CREATE TEMPORARY VIEW parquetTable
USING org.apache.spark.sql.parquet
OPTIONS (
  path "examples/src/main/resources/people.parquet"
)

SELECT * FROM parquetTable

{% endhighlight %}

</div>

</div>

### Partition Discovery

Table partitioning is a common optimization approach used in systems like Hive. In a partitioned
table, data are usually stored in different directories, with partitioning column values encoded in
the path of each partition directory. All built-in file sources (including Text/CSV/JSON/ORC/Parquet)
are able to discover and infer partitioning information automatically.
For example, we can store all our previously used
population data into a partitioned table using the following directory structure, with two extra
columns, `gender` and `country` as partitioning columns:

{% highlight text %}

path
????????? to
    ????????? table
        ????????? gender=male
        ??????? ????????? ...
        ??????? ???
        ??????? ????????? country=US
        ??????? ??????? ????????? data.parquet
        ??????? ????????? country=CN
        ??????? ??????? ????????? data.parquet
        ??????? ????????? ...
        ????????? gender=female
         ???? ????????? ...
         ???? ???
         ???? ????????? country=US
         ???? ??????? ????????? data.parquet
         ???? ????????? country=CN
         ???? ??????? ????????? data.parquet
         ???? ????????? ...

{% endhighlight %}

By passing `path/to/table` to either `SparkSession.read.parquet` or `SparkSession.read.load`, Spark SQL
will automatically extract the partitioning information from the paths.
Now the schema of the returned DataFrame becomes:

{% highlight text %}

root
|-- name: string (nullable = true)
|-- age: long (nullable = true)
|-- gender: string (nullable = true)
|-- country: string (nullable = true)

{% endhighlight %}

Notice that the data types of the partitioning columns are automatically inferred. Currently,
numeric data types, date, timestamp and string type are supported. Sometimes users may not want
to automatically infer the data types of the partitioning columns. For these use cases, the
automatic type inference can be configured by
`spark.sql.sources.partitionColumnTypeInference.enabled`, which is default to `true`. When type
inference is disabled, string type will be used for the partitioning columns.

Starting from Spark 1.6.0, partition discovery only finds partitions under the given paths
by default. For the above example, if users pass `path/to/table/gender=male` to either
`SparkSession.read.parquet` or `SparkSession.read.load`, `gender` will not be considered as a
partitioning column. If users need to specify the base path that partition discovery
should start with, they can set `basePath` in the data source options. For example,
when `path/to/table/gender=male` is the path of the data and
users set `basePath` to `path/to/table/`, `gender` will be a partitioning column.

### Schema Merging

Like ProtocolBuffer, Avro, and Thrift, Parquet also supports schema evolution. Users can start with
a simple schema, and gradually add more columns to the schema as needed. In this way, users may end
up with multiple Parquet files with different but mutually compatible schemas. The Parquet data
source is now able to automatically detect this case and merge schemas of all these files.

Since schema merging is a relatively expensive operation, and is not a necessity in most cases, we
turned it off by default starting from 1.5.0. You may enable it by

1. setting data source option `mergeSchema` to `true` when reading Parquet files (as shown in the
   examples below), or
2. setting the global SQL option `spark.sql.parquet.mergeSchema` to `true`.

<div class="codetabs">

<div data-lang="scala"  markdown="1">
{% include_example schema_merging scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example schema_merging java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">

{% include_example schema_merging python/sql/datasource.py %}
</div>

<div data-lang="r"  markdown="1">

{% include_example schema_merging r/RSparkSQLExample.R %}

</div>

</div>

### Hive metastore Parquet table conversion

When reading from and writing to Hive metastore Parquet tables, Spark SQL will try to use its own
Parquet support instead of Hive SerDe for better performance. This behavior is controlled by the
`spark.sql.hive.convertMetastoreParquet` configuration, and is turned on by default.

#### Hive/Parquet Schema Reconciliation

There are two key differences between Hive and Parquet from the perspective of table schema
processing.

1. Hive is case insensitive, while Parquet is not
1. Hive considers all columns nullable, while nullability in Parquet is significant

Due to this reason, we must reconcile Hive metastore schema with Parquet schema when converting a
Hive metastore Parquet table to a Spark SQL Parquet table. The reconciliation rules are:

1. Fields that have the same name in both schema must have the same data type regardless of
   nullability. The reconciled field should have the data type of the Parquet side, so that
   nullability is respected.

1. The reconciled schema contains exactly those fields defined in Hive metastore schema.

   - Any fields that only appear in the Parquet schema are dropped in the reconciled schema.
   - Any fields that only appear in the Hive metastore schema are added as nullable field in the
     reconciled schema.

#### Metadata Refreshing

Spark SQL caches Parquet metadata for better performance. When Hive metastore Parquet table
conversion is enabled, metadata of those converted tables are also cached. If these tables are
updated by Hive or other external tools, you need to refresh them manually to ensure consistent
metadata.

<div class="codetabs">

<div data-lang="scala"  markdown="1">

{% highlight scala %}
// spark is an existing SparkSession
spark.catalog.refreshTable("my_table")
{% endhighlight %}

</div>

<div data-lang="java"  markdown="1">

{% highlight java %}
// spark is an existing SparkSession
spark.catalog().refreshTable("my_table");
{% endhighlight %}

</div>

<div data-lang="python"  markdown="1">

{% highlight python %}
# spark is an existing SparkSession
spark.catalog.refreshTable("my_table")
{% endhighlight %}

</div>

<div data-lang="r"  markdown="1">

{% highlight r %}
refreshTable("my_table")
{% endhighlight %}

</div>

<div data-lang="sql"  markdown="1">

{% highlight sql %}
REFRESH TABLE my_table;
{% endhighlight %}

</div>

</div>

### Configuration

Configuration of Parquet can be done using the `setConf` method on `SparkSession` or by running
`SET key=value` commands using SQL.

<table class="table">
<tr><th>Property Name</th><th>Default</th><th>Meaning</th></tr>
<tr>
  <td><code>spark.sql.parquet.binaryAsString</code></td>
  <td>false</td>
  <td>
    Some other Parquet-producing systems, in particular Impala, Hive, and older versions of Spark SQL, do
    not differentiate between binary data and strings when writing out the Parquet schema. This
    flag tells Spark SQL to interpret binary data as a string to provide compatibility with these systems.
  </td>
</tr>
<tr>
  <td><code>spark.sql.parquet.int96AsTimestamp</code></td>
  <td>true</td>
  <td>
    Some Parquet-producing systems, in particular Impala and Hive, store Timestamp into INT96. This
    flag tells Spark SQL to interpret INT96 data as a timestamp to provide compatibility with these systems.
  </td>
</tr>
<tr>
  <td><code>spark.sql.parquet.compression.codec</code></td>
  <td>snappy</td>
  <td>
    Sets the compression codec used when writing Parquet files. If either `compression` or
    `parquet.compression` is specified in the table-specific options/properties, the precedence would be
    `compression`, `parquet.compression`, `spark.sql.parquet.compression.codec`. Acceptable values include:
    none, uncompressed, snappy, gzip, lzo, brotli, lz4, zstd.
    Note that `zstd` requires `ZStandardCodec` to be installed before Hadoop 2.9.0, `brotli` requires
    `BrotliCodec` to be installed.
  </td>
</tr>
<tr>
  <td><code>spark.sql.parquet.filterPushdown</code></td>
  <td>true</td>
  <td>Enables Parquet filter push-down optimization when set to true.</td>
</tr>
<tr>
  <td><code>spark.sql.hive.convertMetastoreParquet</code></td>
  <td>true</td>
  <td>
    When set to false, Spark SQL will use the Hive SerDe for parquet tables instead of the built in
    support.
  </td>
</tr>
<tr>
  <td><code>spark.sql.parquet.mergeSchema</code></td>
  <td>false</td>
  <td>
    <p>
      When true, the Parquet data source merges schemas collected from all data files, otherwise the
      schema is picked from the summary file or a random data file if no summary file is available.
    </p>
  </td>
</tr>
<tr>
  <td><code>spark.sql.optimizer.metadataOnly</code></td>
  <td>true</td>
  <td>
    <p>
      When true, enable the metadata-only query optimization that use the table's metadata to
      produce the partition columns instead of table scans. It applies when all the columns scanned
      are partition columns and the query has an aggregate operator that satisfies distinct
      semantics.
    </p>
  </td>
</tr>
<tr>
  <td><code>spark.sql.parquet.writeLegacyFormat</code></td>
  <td>false</td>
  <td>
    If true, data will be written in a way of Spark 1.4 and earlier. For example, decimal values
    will be written in Apache Parquet's fixed-length byte array format, which other systems such as
    Apache Hive and Apache Impala use. If false, the newer format in Parquet will be used. For
    example, decimals will be written in int-based format. If Parquet output is intended for use
    with systems that do not support this newer format, set to true.
  </td>
</tr>
</table>

## ORC Files

Since Spark 2.3, Spark supports a vectorized ORC reader with a new ORC file format for ORC files.
To do that, the following configurations are newly added. The vectorized reader is used for the
native ORC tables (e.g., the ones created using the clause `USING ORC`) when `spark.sql.orc.impl`
is set to `native` and `spark.sql.orc.enableVectorizedReader` is set to `true`. For the Hive ORC
serde tables (e.g., the ones created using the clause `USING HIVE OPTIONS (fileFormat 'ORC')`),
the vectorized reader is used when `spark.sql.hive.convertMetastoreOrc` is also set to `true`.

<table class="table">
  <tr><th><b>Property Name</b></th><th><b>Default</b></th><th><b>Meaning</b></th></tr>
  <tr>
    <td><code>spark.sql.orc.impl</code></td>
    <td><code>native</code></td>
    <td>The name of ORC implementation. It can be one of <code>native</code> and <code>hive</code>. <code>native</code> means the native ORC support that is built on Apache ORC 1.4. `hive` means the ORC library in Hive 1.2.1.</td>
  </tr>
  <tr>
    <td><code>spark.sql.orc.enableVectorizedReader</code></td>
    <td><code>true</code></td>
    <td>Enables vectorized orc decoding in <code>native</code> implementation. If <code>false</code>, a new non-vectorized ORC reader is used in <code>native</code> implementation. For <code>hive</code> implementation, this is ignored.</td>
  </tr>
</table>

## JSON Datasets
<div class="codetabs">

<div data-lang="scala"  markdown="1">
Spark SQL can automatically infer the schema of a JSON dataset and load it as a `Dataset[Row]`.
This conversion can be done using `SparkSession.read.json()` on either a `Dataset[String]`,
or a JSON file.

Note that the file that is offered as _a json file_ is not a typical JSON file. Each
line must contain a separate, self-contained valid JSON object. For more information, please see
[JSON Lines text format, also called newline-delimited JSON](http://jsonlines.org/).

For a regular multi-line JSON file, set the `multiLine` option to `true`.

{% include_example json_dataset scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
Spark SQL can automatically infer the schema of a JSON dataset and load it as a `Dataset<Row>`.
This conversion can be done using `SparkSession.read().json()` on either a `Dataset<String>`,
or a JSON file.

Note that the file that is offered as _a json file_ is not a typical JSON file. Each
line must contain a separate, self-contained valid JSON object. For more information, please see
[JSON Lines text format, also called newline-delimited JSON](http://jsonlines.org/).

For a regular multi-line JSON file, set the `multiLine` option to `true`.

{% include_example json_dataset java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">
Spark SQL can automatically infer the schema of a JSON dataset and load it as a DataFrame.
This conversion can be done using `SparkSession.read.json` on a JSON file.

Note that the file that is offered as _a json file_ is not a typical JSON file. Each
line must contain a separate, self-contained valid JSON object. For more information, please see
[JSON Lines text format, also called newline-delimited JSON](http://jsonlines.org/).

For a regular multi-line JSON file, set the `multiLine` parameter to `True`.

{% include_example json_dataset python/sql/datasource.py %}
</div>

<div data-lang="r"  markdown="1">
Spark SQL can automatically infer the schema of a JSON dataset and load it as a DataFrame. using
the `read.json()` function, which loads data from a directory of JSON files where each line of the
files is a JSON object.

Note that the file that is offered as _a json file_ is not a typical JSON file. Each
line must contain a separate, self-contained valid JSON object. For more information, please see
[JSON Lines text format, also called newline-delimited JSON](http://jsonlines.org/).

For a regular multi-line JSON file, set a named parameter `multiLine` to `TRUE`.

{% include_example json_dataset r/RSparkSQLExample.R %}

</div>

<div data-lang="sql"  markdown="1">

{% highlight sql %}

CREATE TEMPORARY VIEW jsonTable
USING org.apache.spark.sql.json
OPTIONS (
  path "examples/src/main/resources/people.json"
)

SELECT * FROM jsonTable

{% endhighlight %}

</div>

</div>

## Hive Tables

Spark SQL also supports reading and writing data stored in [Apache Hive](http://hive.apache.org/).
However, since Hive has a large number of dependencies, these dependencies are not included in the
default Spark distribution. If Hive dependencies can be found on the classpath, Spark will load them
automatically. Note that these Hive dependencies must also be present on all of the worker nodes, as
they will need access to the Hive serialization and deserialization libraries (SerDes) in order to
access data stored in Hive.

Configuration of Hive is done by placing your `hive-site.xml`, `core-site.xml` (for security configuration),
and `hdfs-site.xml` (for HDFS configuration) file in `conf/`.

When working with Hive, one must instantiate `SparkSession` with Hive support, including
connectivity to a persistent Hive metastore, support for Hive serdes, and Hive user-defined functions.
Users who do not have an existing Hive deployment can still enable Hive support. When not configured
by the `hive-site.xml`, the context automatically creates `metastore_db` in the current directory and
creates a directory configured by `spark.sql.warehouse.dir`, which defaults to the directory
`spark-warehouse` in the current directory that the Spark application is started. Note that
the `hive.metastore.warehouse.dir` property in `hive-site.xml` is deprecated since Spark 2.0.0.
Instead, use `spark.sql.warehouse.dir` to specify the default location of database in warehouse.
You may need to grant write privilege to the user who starts the Spark application.

<div class="codetabs">

<div data-lang="scala"  markdown="1">
{% include_example spark_hive scala/org/apache/spark/examples/sql/hive/SparkHiveExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example spark_hive java/org/apache/spark/examples/sql/hive/JavaSparkHiveExample.java %}
</div>

<div data-lang="python"  markdown="1">
{% include_example spark_hive python/sql/hive.py %}
</div>

<div data-lang="r"  markdown="1">

When working with Hive one must instantiate `SparkSession` with Hive support. This
adds support for finding tables in the MetaStore and writing queries using HiveQL.

{% include_example spark_hive r/RSparkSQLExample.R %}

</div>
</div>

### Specifying storage format for Hive tables

When you create a Hive table, you need to define how this table should read/write data from/to file system,
i.e. the "input format" and "output format". You also need to define how this table should deserialize the data
to rows, or serialize rows to data, i.e. the "serde". The following options can be used to specify the storage
format("serde", "input format", "output format"), e.g. `CREATE TABLE src(id int) USING hive OPTIONS(fileFormat 'parquet')`.
By default, we will read the table files as plain text. Note that, Hive storage handler is not supported yet when
creating table, you can create a table using storage handler at Hive side, and use Spark SQL to read it.

<table class="table">
  <tr><th>Property Name</th><th>Meaning</th></tr>
  <tr>
    <td><code>fileFormat</code></td>
    <td>
      A fileFormat is kind of a package of storage format specifications, including "serde", "input format" and
      "output format". Currently we support 6 fileFormats: 'sequencefile', 'rcfile', 'orc', 'parquet', 'textfile' and 'avro'.
    </td>
  </tr>

  <tr>
    <td><code>inputFormat, outputFormat</code></td>
    <td>
      These 2 options specify the name of a corresponding `InputFormat` and `OutputFormat` class as a string literal,
      e.g. `org.apache.hadoop.hive.ql.io.orc.OrcInputFormat`. These 2 options must be appeared in pair, and you can not
      specify them if you already specified the `fileFormat` option.
    </td>
  </tr>

  <tr>
    <td><code>serde</code></td>
    <td>
      This option specifies the name of a serde class. When the `fileFormat` option is specified, do not specify this option
      if the given `fileFormat` already include the information of serde. Currently "sequencefile", "textfile" and "rcfile"
      don't include the serde information and you can use this option with these 3 fileFormats.
    </td>
  </tr>

  <tr>
    <td><code>fieldDelim, escapeDelim, collectionDelim, mapkeyDelim, lineDelim</code></td>
    <td>
      These options can only be used with "textfile" fileFormat. They define how to read delimited files into rows.
    </td>
  </tr>
</table>

All other properties defined with `OPTIONS` will be regarded as Hive serde properties.

### Interacting with Different Versions of Hive Metastore

One of the most important pieces of Spark SQL's Hive support is interaction with Hive metastore,
which enables Spark SQL to access metadata of Hive tables. Starting from Spark 1.4.0, a single binary
build of Spark SQL can be used to query different versions of Hive metastores, using the configuration described below.
Note that independent of the version of Hive that is being used to talk to the metastore, internally Spark SQL
will compile against Hive 1.2.1 and use those classes for internal execution (serdes, UDFs, UDAFs, etc).

The following options can be used to configure the version of Hive that is used to retrieve metadata:

<table class="table">
  <tr><th>Property Name</th><th>Default</th><th>Meaning</th></tr>
  <tr>
    <td><code>spark.sql.hive.metastore.version</code></td>
    <td><code>1.2.1</code></td>
    <td>
      Version of the Hive metastore. Available
      options are <code>0.12.0</code> through <code>2.3.3</code>.
    </td>
  </tr>
  <tr>
    <td><code>spark.sql.hive.metastore.jars</code></td>
    <td><code>builtin</code></td>
    <td>
      Location of the jars that should be used to instantiate the HiveMetastoreClient. This
      property can be one of three options:
      <ol>
        <li><code>builtin</code></li>
        Use Hive 1.2.1, which is bundled with the Spark assembly when <code>-Phive</code> is
        enabled. When this option is chosen, <code>spark.sql.hive.metastore.version</code> must be
        either <code>1.2.1</code> or not defined.
        <li><code>maven</code></li>
        Use Hive jars of specified version downloaded from Maven repositories. This configuration
        is not generally recommended for production deployments.
        <li>A classpath in the standard format for the JVM. This classpath must include all of Hive
        and its dependencies, including the correct version of Hadoop. These jars only need to be
        present on the driver, but if you are running in yarn cluster mode then you must ensure
        they are packaged with your application.</li>
      </ol>
    </td>
  </tr>
  <tr>
    <td><code>spark.sql.hive.metastore.sharedPrefixes</code></td>
    <td><code>com.mysql.jdbc,<br/>org.postgresql,<br/>com.microsoft.sqlserver,<br/>oracle.jdbc</code></td>
    <td>
      <p>
        A comma-separated list of class prefixes that should be loaded using the classloader that is
        shared between Spark SQL and a specific version of Hive. An example of classes that should
        be shared is JDBC drivers that are needed to talk to the metastore. Other classes that need
        to be shared are those that interact with classes that are already shared. For example,
        custom appenders that are used by log4j.
      </p>
    </td>
  </tr>
  <tr>
    <td><code>spark.sql.hive.metastore.barrierPrefixes</code></td>
    <td><code>(empty)</code></td>
    <td>
      <p>
        A comma separated list of class prefixes that should explicitly be reloaded for each version
        of Hive that Spark SQL is communicating with. For example, Hive UDFs that are declared in a
        prefix that typically would be shared (i.e. <code>org.apache.spark.*</code>).
      </p>
    </td>
  </tr>
</table>


## JDBC To Other Databases

Spark SQL also includes a data source that can read data from other databases using JDBC. This
functionality should be preferred over using [JdbcRDD](api/scala/index.html#org.apache.spark.rdd.JdbcRDD).
This is because the results are returned
as a DataFrame and they can easily be processed in Spark SQL or joined with other data sources.
The JDBC data source is also easier to use from Java or Python as it does not require the user to
provide a ClassTag.
(Note that this is different than the Spark SQL JDBC server, which allows other applications to
run queries using Spark SQL).

To get started you will need to include the JDBC driver for your particular database on the
spark classpath. For example, to connect to postgres from the Spark Shell you would run the
following command:

{% highlight bash %}
bin/spark-shell --driver-class-path postgresql-9.4.1207.jar --jars postgresql-9.4.1207.jar
{% endhighlight %}

Tables from the remote database can be loaded as a DataFrame or Spark SQL temporary view using
the Data Sources API. Users can specify the JDBC connection properties in the data source options.
<code>user</code> and <code>password</code> are normally provided as connection properties for
logging into the data sources. In addition to the connection properties, Spark also supports
the following case-insensitive options:

<table class="table">
  <tr><th>Property Name</th><th>Meaning</th></tr>
  <tr>
    <td><code>url</code></td>
    <td>
      The JDBC URL to connect to. The source-specific connection properties may be specified in the URL. e.g., <code>jdbc:postgresql://localhost/test?user=fred&password=secret</code>
    </td>
  </tr>

  <tr>
    <td><code>dbtable</code></td>
    <td>
      The JDBC table that should be read from or written into. Note that when using it in the read
      path anything that is valid in a <code>FROM</code> clause of a SQL query can be used.
      For example, instead of a full table you could also use a subquery in parentheses. It is not
      allowed to specify `dbtable` and `query` options at the same time.
    </td>
  </tr>
  <tr>
    <td><code>query</code></td>
    <td>
      A query that will be used to read data into Spark. The specified query will be parenthesized and used
      as a subquery in the <code>FROM</code> clause. Spark will also assign an alias to the subquery clause.
      As an example, spark will issue a query of the following form to the JDBC Source.<br><br>
      <code> SELECT &lt;columns&gt; FROM (&lt;user_specified_query&gt;) spark_gen_alias</code><br><br>
      Below are couple of restrictions while using this option.<br>
      <ol>
         <li> It is not allowed to specify `dbtable` and `query` options at the same time. </li>
         <li> It is not allowed to spcify `query` and `partitionColumn` options at the same time. When specifying
            `partitionColumn` option is required, the subquery can be specified using `dbtable` option instead and
            partition columns can be qualified using the subquery alias provided as part of `dbtable`. <br>
            Example:<br>
            <code>
               spark.read.format("jdbc")<br>
               &nbsp&nbsp .option("dbtable", "(select c1, c2 from t1) as subq")<br>
               &nbsp&nbsp .option("partitionColumn", "subq.c1"<br>
               &nbsp&nbsp .load()
            </code></li>
      </ol>
    </td>
  </tr>

  <tr>
    <td><code>driver</code></td>
    <td>
      The class name of the JDBC driver to use to connect to this URL.
    </td>
  </tr>

  <tr>
    <td><code>partitionColumn, lowerBound, upperBound</code></td>
    <td>
      These options must all be specified if any of them is specified. In addition,
      <code>numPartitions</code> must be specified. They describe how to partition the table when
      reading in parallel from multiple workers.
      <code>partitionColumn</code> must be a numeric, date, or timestamp column from the table in question.
      Notice that <code>lowerBound</code> and <code>upperBound</code> are just used to decide the
      partition stride, not for filtering the rows in table. So all rows in the table will be
      partitioned and returned. This option applies only to reading.
    </td>
  </tr>

  <tr>
     <td><code>numPartitions</code></td>
     <td>
       The maximum number of partitions that can be used for parallelism in table reading and
       writing. This also determines the maximum number of concurrent JDBC connections.
       If the number of partitions to write exceeds this limit, we decrease it to this limit by
       calling <code>coalesce(numPartitions)</code> before writing.
     </td>
  </tr>

  <tr>
    <td><code>queryTimeout</code></td>
    <td>
      The number of seconds the driver will wait for a Statement object to execute to the given
      number of seconds. Zero means there is no limit. In the write path, this option depends on
      how JDBC drivers implement the API <code>setQueryTimeout</code>, e.g., the h2 JDBC driver
      checks the timeout of each query instead of an entire JDBC batch.
      It defaults to <code>0</code>.
    </td>
  </tr>

  <tr>
    <td><code>fetchsize</code></td>
    <td>
      The JDBC fetch size, which determines how many rows to fetch per round trip. This can help performance on JDBC drivers which default to low fetch size (eg. Oracle with 10 rows). This option applies only to reading.
    </td>
  </tr>

  <tr>
     <td><code>batchsize</code></td>
     <td>
       The JDBC batch size, which determines how many rows to insert per round trip. This can help performance on JDBC drivers. This option applies only to writing. It defaults to <code>1000</code>.
     </td>
  </tr>

  <tr>
     <td><code>isolationLevel</code></td>
     <td>
       The transaction isolation level, which applies to current connection. It can be one of <code>NONE</code>, <code>READ_COMMITTED</code>, <code>READ_UNCOMMITTED</code>, <code>REPEATABLE_READ</code>, or <code>SERIALIZABLE</code>, corresponding to standard transaction isolation levels defined by JDBC's Connection object, with default of <code>READ_UNCOMMITTED</code>. This option applies only to writing. Please refer the documentation in <code>java.sql.Connection</code>.
     </td>
   </tr>

  <tr>
     <td><code>sessionInitStatement</code></td>
     <td>
       After each database session is opened to the remote DB and before starting to read data, this option executes a custom SQL statement (or a PL/SQL block). Use this to implement session initialization code. Example: <code>option("sessionInitStatement", """BEGIN execute immediate 'alter session set "_serial_direct_read"=true'; END;""")</code>
     </td>
  </tr>

  <tr>
    <td><code>truncate</code></td>
    <td>
     This is a JDBC writer related option. When <code>SaveMode.Overwrite</code> is enabled, this option causes Spark to truncate an existing table instead of dropping and recreating it. This can be more efficient, and prevents the table metadata (e.g., indices) from being removed. However, it will not work in some cases, such as when the new data has a different schema. It defaults to <code>false</code>. This option applies only to writing.
   </td>
  </tr>
  
  <tr>
    <td><code>cascadeTruncate</code></td>
    <td>
        This is a JDBC writer related option. If enabled and supported by the JDBC database (PostgreSQL and Oracle at the moment), this options allows execution of a <code>TRUNCATE TABLE t CASCADE</code> (in the case of PostgreSQL a <code>TRUNCATE TABLE ONLY t CASCADE</code> is executed to prevent inadvertently truncating descendant tables). This will affect other tables, and thus should be used with care. This option applies only to writing. It defaults to the default cascading truncate behaviour of the JDBC database in question, specified in the <code>isCascadeTruncate</code> in each JDBCDialect.
    </td>
  </tr>

  <tr>
    <td><code>createTableOptions</code></td>
    <td>
     This is a JDBC writer related option. If specified, this option allows setting of database-specific table and partition options when creating a table (e.g., <code>CREATE TABLE t (name string) ENGINE=InnoDB.</code>). This option applies only to writing.
   </td>
  </tr>

  <tr>
    <td><code>createTableColumnTypes</code></td>
    <td>
     The database column data types to use instead of the defaults, when creating the table. Data type information should be specified in the same format as CREATE TABLE columns syntax (e.g: <code>"name CHAR(64), comments VARCHAR(1024)")</code>. The specified types should be valid spark sql data types. This option applies only to writing.
    </td>
  </tr>

  <tr>
    <td><code>customSchema</code></td>
    <td>
     The custom schema to use for reading data from JDBC connectors. For example, <code>"id DECIMAL(38, 0), name STRING"</code>. You can also specify partial fields, and the others use the default type mapping. For example, <code>"id DECIMAL(38, 0)"</code>. The column names should be identical to the corresponding column names of JDBC table. Users can specify the corresponding data types of Spark SQL instead of using the defaults. This option applies only to reading.
    </td>
  </tr>

  <tr>
    <td><code>pushDownPredicate</code></td>
    <td>
     The option to enable or disable predicate push-down into the JDBC data source. The default value is true, in which case Spark will push down filters to the JDBC data source as much as possible. Otherwise, if set to false, no filter will be pushed down to the JDBC data source and thus all filters will be handled by Spark. Predicate push-down is usually turned off when the predicate filtering is performed faster by Spark than by the JDBC data source.
    </td>
  </tr>
</table>

<div class="codetabs">

<div data-lang="scala"  markdown="1">
{% include_example jdbc_dataset scala/org/apache/spark/examples/sql/SQLDataSourceExample.scala %}
</div>

<div data-lang="java"  markdown="1">
{% include_example jdbc_dataset java/org/apache/spark/examples/sql/JavaSQLDataSourceExample.java %}
</div>

<div data-lang="python"  markdown="1">
{% include_example jdbc_dataset python/sql/datasource.py %}
</div>

<div data-lang="r"  markdown="1">
{% include_example jdbc_dataset r/RSparkSQLExample.R %}
</div>

<div data-lang="sql"  markdown="1">

{% highlight sql %}

CREATE TEMPORARY VIEW jdbcTable
USING org.apache.spark.sql.jdbc
OPTIONS (
  url "jdbc:postgresql:dbserver",
  dbtable "schema.tablename",
  user 'username',
  password 'password'
)

INSERT INTO TABLE jdbcTable
SELECT * FROM resultTable
{% endhighlight %}

</div>
</div>

## Avro Files
See the [Apache Avro Data Source Guide](avro-data-source-guide.html).

## Troubleshooting

 * The JDBC driver class must be visible to the primordial class loader on the client session and on all executors. This is because Java's DriverManager class does a security check that results in it ignoring all drivers not visible to the primordial class loader when one goes to open a connection. One convenient way to do this is to modify compute_classpath.sh on all worker nodes to include your driver JARs.
 * Some databases, such as H2, convert all names to upper case. You'll need to use upper case to refer to those names in Spark SQL.
 * Users can specify vendor-specific JDBC connection properties in the data source options to do special treatment. For example, `spark.read.format("jdbc").option("url", oracleJdbcUrl).option("oracle.jdbc.mapDateToTimestamp", "false")`. `oracle.jdbc.mapDateToTimestamp` defaults to true, users often need to disable this flag to avoid Oracle date being resolved as timestamp.

# Performance Tuning

For some workloads, it is possible to improve performance by either caching data in memory, or by
turning on some experimental options.

## Caching Data In Memory

Spark SQL can cache tables using an in-memory columnar format by calling `spark.catalog.cacheTable("tableName")` or `dataFrame.cache()`.
Then Spark SQL will scan only required columns and will automatically tune compression to minimize
memory usage and GC pressure. You can call `spark.catalog.uncacheTable("tableName")` to remove the table from memory.

Configuration of in-memory caching can be done using the `setConf` method on `SparkSession` or by running
`SET key=value` commands using SQL.

<table class="table">
<tr><th>Property Name</th><th>Default</th><th>Meaning</th></tr>
<tr>
  <td><code>spark.sql.inMemoryColumnarStorage.compressed</code></td>
  <td>true</td>
  <td>
    When set to true Spark SQL will automatically select a compression codec for each column based
    on statistics of the data.
  </td>
</tr>
<tr>
  <td><code>spark.sql.inMemoryColumnarStorage.batchSize</code></td>
  <td>10000</td>
  <td>
    Controls the size of batches for columnar caching. Larger batch sizes can improve memory utilization
    and compression, but risk OOMs when caching data.
  </td>
</tr>

</table>

## Other Configuration Options

The following options can also be used to tune the performance of query execution. It is possible
that these options will be deprecated in future release as more optimizations are performed automatically.

<table class="table">
  <tr><th>Property Name</th><th>Default</th><th>Meaning</th></tr>
  <tr>
    <td><code>spark.sql.files.maxPartitionBytes</code></td>
    <td>134217728 (128 MB)</td>
    <td>
      The maximum number of bytes to pack into a single partition when reading files.
    </td>
  </tr>
  <tr>
    <td><code>spark.sql.files.openCostInBytes</code></td>
    <td>4194304 (4 MB)</td>
    <td>
      The estimated cost to open a file, measured by the number of bytes could be scanned in the same
      time. This is used when putting multiple files into a partition. It is better to over estimated,
      then the partitions with small files will be faster than partitions with bigger files (which is
      scheduled first).
    </td>
  </tr>
  <tr>
    <td><code>spark.sql.broadcastTimeout</code></td>
    <td>300</td>
    <td>
    <p>
      Timeout in seconds for the broadcast wait time in broadcast joins
    </p>
    </td>
  </tr>
  <tr>
    <td><code>spark.sql.autoBroadcastJoinThreshold</code></td>
    <td>10485760 (10 MB)</td>
    <td>
      Configures the maximum size in bytes for a table that will be broadcast to all worker nodes when
      performing a join. By setting this value to -1 broadcasting can be disabled. Note that currently
      statistics are only supported for Hive Metastore tables where the command
      <code>ANALYZE TABLE &lt;tableName&gt; COMPUTE STATISTICS noscan</code> has been run.
    </td>
  </tr>
  <tr>
    <td><code>spark.sql.shuffle.partitions</code></td>
    <td>200</td>
    <td>
      Configures the number of partitions to use when shuffling data for joins or aggregations.
    </td>
  </tr>
</table>

## Broadcast Hint for SQL Queries

The `BROADCAST` hint guides Spark to broadcast each specified table when joining them with another table or view.
When Spark deciding the join methods, the broadcast hash join (i.e., BHJ) is preferred,
even if the statistics is above the configuration `spark.sql.autoBroadcastJoinThreshold`.
When both sides of a join are specified, Spark broadcasts the one having the lower statistics.
Note Spark does not guarantee BHJ is always chosen, since not all cases (e.g. full outer join)
support BHJ. When the broadcast nested loop join is selected, we still respect the hint.

<div class="codetabs">

<div data-lang="scala"  markdown="1">

{% highlight scala %}
import org.apache.spark.sql.functions.broadcast
broadcast(spark.table("src")).join(spark.table("records"), "key").show()
{% endhighlight %}

</div>

<div data-lang="java"  markdown="1">

{% highlight java %}
import static org.apache.spark.sql.functions.broadcast;
broadcast(spark.table("src")).join(spark.table("records"), "key").show();
{% endhighlight %}

</div>

<div data-lang="python"  markdown="1">

{% highlight python %}
from pyspark.sql.functions import broadcast
broadcast(spark.table("src")).join(spark.table("records"), "key").show()
{% endhighlight %}

</div>

<div data-lang="r"  markdown="1">

{% highlight r %}
src <- sql("SELECT * FROM src")
records <- sql("SELECT * FROM records")
head(join(broadcast(src), records, src$key == records$key))
{% endhighlight %}

</div>

<div data-lang="sql"  markdown="1">

{% highlight sql %}
-- We accept BROADCAST, BROADCASTJOIN and MAPJOIN for broadcast hint
SELECT /*+ BROADCAST(r) */ * FROM records r JOIN src s ON r.key = s.key
{% endhighlight %}

</div>
</div>

# Distributed SQL Engine

Spark SQL can also act as a distributed query engine using its JDBC/ODBC or command-line interface.
In this mode, end-users or applications can interact with Spark SQL directly to run SQL queries,
without the need to write any code.

## Running the Thrift JDBC/ODBC server

The Thrift JDBC/ODBC server implemented here corresponds to the [`HiveServer2`](https://cwiki.apache.org/confluence/display/Hive/Setting+Up+HiveServer2)
in Hive 1.2.1 You can test the JDBC server with the beeline script that comes with either Spark or Hive 1.2.1.

To start the JDBC/ODBC server, run the following in the Spark directory:

    ./sbin/start-thriftserver.sh

This script accepts all `bin/spark-submit` command line options, plus a `--hiveconf` option to
specify Hive properties. You may run `./sbin/start-thriftserver.sh --help` for a complete list of
all available options. By default, the server listens on localhost:10000. You may override this
behaviour via either environment variables, i.e.:

{% highlight bash %}
export HIVE_SERVER2_THRIFT_PORT=<listening-port>
export HIVE_SERVER2_THRIFT_BIND_HOST=<listening-host>
./sbin/start-thriftserver.sh \
  --master <master-uri> \
  ...
{% endhighlight %}

or system properties:

{% highlight bash %}
./sbin/start-thriftserver.sh \
  --hiveconf hive.server2.thrift.port=<listening-port> \
  --hiveconf hive.server2.thrift.bind.host=<listening-host> \
  --master <master-uri>
  ...
{% endhighlight %}

Now you can use beeline to test the Thrift JDBC/ODBC server:

    ./bin/beeline

Connect to the JDBC/ODBC server in beeline with:

    beeline> !connect jdbc:hive2://localhost:10000

Beeline will ask you for a username and password. In non-secure mode, simply enter the username on
your machine and a blank password. For secure mode, please follow the instructions given in the
[beeline documentation](https://cwiki.apache.org/confluence/display/Hive/HiveServer2+Clients).

Configuration of Hive is done by placing your `hive-site.xml`, `core-site.xml` and `hdfs-site.xml` files in `conf/`.

You may also use the beeline script that comes with Hive.

Thrift JDBC server also supports sending thrift RPC messages over HTTP transport.
Use the following setting to enable HTTP mode as system property or in `hive-site.xml` file in `conf/`:

    hive.server2.transport.mode - Set this to value: http
    hive.server2.thrift.http.port - HTTP port number to listen on; default is 10001
    hive.server2.http.endpoint - HTTP endpoint; default is cliservice

To test, use beeline to connect to the JDBC/ODBC server in http mode with:

    beeline> !connect jdbc:hive2://<host>:<port>/<database>?hive.server2.transport.mode=http;hive.server2.thrift.http.path=<http_endpoint>


## Running the Spark SQL CLI

The Spark SQL CLI is a convenient tool to run the Hive metastore service in local mode and execute
queries input from the command line. Note that the Spark SQL CLI cannot talk to the Thrift JDBC server.

To start the Spark SQL CLI, run the following in the Spark directory:

    ./bin/spark-sql

Configuration of Hive is done by placing your `hive-site.xml`, `core-site.xml` and `hdfs-site.xml` files in `conf/`.
You may run `./bin/spark-sql --help` for a complete list of all available
options.

# PySpark Usage Guide for Pandas with Apache Arrow

## Apache Arrow in Spark

Apache Arrow is an in-memory columnar data format that is used in Spark to efficiently transfer
data between JVM and Python processes. This currently is most beneficial to Python users that
work with Pandas/NumPy data. Its usage is not automatic and might require some minor
changes to configuration or code to take full advantage and ensure compatibility. This guide will
give a high-level description of how to use Arrow in Spark and highlight any differences when
working with Arrow-enabled data.

### Ensure PyArrow Installed

If you install PySpark using pip, then PyArrow can be brought in as an extra dependency of the
SQL module with the command `pip install pyspark[sql]`. Otherwise, you must ensure that PyArrow
is installed and available on all cluster nodes. The current supported version is 0.8.0.
You can install using pip or conda from the conda-forge channel. See PyArrow
[installation](https://arrow.apache.org/docs/python/install.html) for details.

## Enabling for Conversion to/from Pandas

Arrow is available as an optimization when converting a Spark DataFrame to a Pandas DataFrame
using the call `toPandas()` and when creating a Spark DataFrame from a Pandas DataFrame with
`createDataFrame(pandas_df)`. To use Arrow when executing these calls, users need to first set
the Spark configuration 'spark.sql.execution.arrow.enabled' to 'true'. This is disabled by default.

In addition, optimizations enabled by 'spark.sql.execution.arrow.enabled' could fallback automatically
to non-Arrow optimization implementation if an error occurs before the actual computation within Spark.
This can be controlled by 'spark.sql.execution.arrow.fallback.enabled'.

<div class="codetabs">
<div data-lang="python" markdown="1">
{% include_example dataframe_with_arrow python/sql/arrow.py %}
</div>
</div>

Using the above optimizations with Arrow will produce the same results as when Arrow is not
enabled. Note that even with Arrow, `toPandas()` results in the collection of all records in the
DataFrame to the driver program and should be done on a small subset of the data. Not all Spark
data types are currently supported and an error can be raised if a column has an unsupported type,
see [Supported SQL Types](#supported-sql-types). If an error occurs during `createDataFrame()`,
Spark will fall back to create the DataFrame without Arrow.

## Pandas UDFs (a.k.a. Vectorized UDFs)

Pandas UDFs are user defined functions that are executed by Spark using Arrow to transfer data and
Pandas to work with the data. A Pandas UDF is defined using the keyword `pandas_udf` as a decorator
or to wrap the function, no additional configuration is required. Currently, there are two types of
Pandas UDF: Scalar and Grouped Map.

### Scalar

Scalar Pandas UDFs are used for vectorizing scalar operations. They can be used with functions such
as `select` and `withColumn`. The Python function should take `pandas.Series` as inputs and return
a `pandas.Series` of the same length. Internally, Spark will execute a Pandas UDF by splitting
columns into batches and calling the function for each batch as a subset of the data, then
concatenating the results together.

The following example shows how to create a scalar Pandas UDF that computes the product of 2 columns.

<div class="codetabs">
<div data-lang="python" markdown="1">
{% include_example scalar_pandas_udf python/sql/arrow.py %}
</div>
</div>

### Grouped Map
Grouped map Pandas UDFs are used with `groupBy().apply()` which implements the "split-apply-combine" pattern.
Split-apply-combine consists of three steps:
* Split the data into groups by using `DataFrame.groupBy`.
* Apply a function on each group. The input and output of the function are both `pandas.DataFrame`. The
  input data contains all the rows and columns for each group.
* Combine the results into a new `DataFrame`.

To use `groupBy().apply()`, the user needs to define the following:
* A Python function that defines the computation for each group.
* A `StructType` object or a string that defines the schema of the output `DataFrame`.

The column labels of the returned `pandas.DataFrame` must either match the field names in the
defined output schema if specified as strings, or match the field data types by position if not
strings, e.g. integer indices. See [pandas.DataFrame](https://pandas.pydata.org/pandas-docs/stable/generated/pandas.DataFrame.html#pandas.DataFrame)
on how to label columns when constructing a `pandas.DataFrame`.

Note that all data for a group will be loaded into memory before the function is applied. This can
lead to out of memory exceptions, especially if the group sizes are skewed. The configuration for
[maxRecordsPerBatch](#setting-arrow-batch-size) is not applied on groups and it is up to the user
to ensure that the grouped data will fit into the available memory.

The following example shows how to use `groupby().apply()` to subtract the mean from each value in the group.

<div class="codetabs">
<div data-lang="python" markdown="1">
{% include_example grouped_map_pandas_udf python/sql/arrow.py %}
</div>
</div>

For detailed usage, please see [`pyspark.sql.functions.pandas_udf`](api/python/pyspark.sql.html#pyspark.sql.functions.pandas_udf) and
[`pyspark.sql.GroupedData.apply`](api/python/pyspark.sql.html#pyspark.sql.GroupedData.apply).

### Grouped Aggregate

Grouped aggregate Pandas UDFs are similar to Spark aggregate functions. Grouped aggregate Pandas UDFs are used with `groupBy().agg()` and
[`pyspark.sql.Window`](api/python/pyspark.sql.html#pyspark.sql.Window). It defines an aggregation from one or more `pandas.Series`
to a scalar value, where each `pandas.Series` represents a column within the group or window.

Note that this type of UDF does not support partial aggregation and all data for a group or window will be loaded into memory. Also,
only unbounded window is supported with Grouped aggregate Pandas UDFs currently.

The following example shows how to use this type of UDF to compute mean with groupBy and window operations:

<div class="codetabs">
<div data-lang="python" markdown="1">
{% include_example grouped_agg_pandas_udf python/sql/arrow.py %}
</div>
</div>

For detailed usage, please see [`pyspark.sql.functions.pandas_udf`](api/python/pyspark.sql.html#pyspark.sql.functions.pandas_udf)

## Usage Notes

### Supported SQL Types

Currently, all Spark SQL data types are supported by Arrow-based conversion except `BinaryType`, `MapType`,
`ArrayType` of `TimestampType`, and nested `StructType`.

### Setting Arrow Batch Size

Data partitions in Spark are converted into Arrow record batches, which can temporarily lead to
high memory usage in the JVM. To avoid possible out of memory exceptions, the size of the Arrow
record batches can be adjusted by setting the conf "spark.sql.execution.arrow.maxRecordsPerBatch"
to an integer that will determine the maximum number of rows for each batch. The default value is
10,000 records per batch. If the number of columns is large, the value should be adjusted
accordingly. Using this limit, each data partition will be made into 1 or more record batches for
processing.

### Timestamp with Time Zone Semantics

Spark internally stores timestamps as UTC values, and timestamp data that is brought in without
a specified time zone is converted as local time to UTC with microsecond resolution. When timestamp
data is exported or displayed in Spark, the session time zone is used to localize the timestamp
values. The session time zone is set with the configuration 'spark.sql.session.timeZone' and will
default to the JVM system local time zone if not set. Pandas uses a `datetime64` type with nanosecond
resolution, `datetime64[ns]`, with optional time zone on a per-column basis.

When timestamp data is transferred from Spark to Pandas it will be converted to nanoseconds
and each column will be converted to the Spark session time zone then localized to that time
zone, which removes the time zone and displays values as local time. This will occur
when calling `toPandas()` or `pandas_udf` with timestamp columns.

When timestamp data is transferred from Pandas to Spark, it will be converted to UTC microseconds. This
occurs when calling `createDataFrame` with a Pandas DataFrame or when returning a timestamp from a
`pandas_udf`. These conversions are done automatically to ensure Spark will have data in the
expected format, so it is not necessary to do any of these conversions yourself. Any nanosecond
values will be truncated.

Note that a standard UDF (non-Pandas) will load timestamp data as Python datetime objects, which is
different than a Pandas timestamp. It is recommended to use Pandas time series functionality when
working with timestamps in `pandas_udf`s to get the best performance, see
[here](https://pandas.pydata.org/pandas-docs/stable/timeseries.html) for details.

# Migration Guide

## Upgrading From Spark SQL 2.3 to 2.4

  - In Spark version 2.3 and earlier, the second parameter to array_contains function is implicitly promoted to the element type of first array type parameter. This type promotion can be lossy and may cause `array_contains` function to return wrong result. This problem has been addressed in 2.4 by employing a safer type promotion mechanism. This can cause some change in behavior and are illustrated in the table below.
  <table class="table">
        <tr>
          <th>
            <b>Query</b>
          </th>
          <th>
            <b>Result Spark 2.3 or Prior</b>
          </th>
          <th>
            <b>Result Spark 2.4</b>
          </th>
          <th>
            <b>Remarks</b>
          </th>
        </tr>
        <tr>
          <th>
            <b>SELECT <br> array_contains(array(1), 1.34D);</b>
          </th>
          <th>
            <b>true</b>
          </th>
          <th>
            <b>false</b>
          </th>
          <th>
            <b>In Spark 2.4, left and right parameters are  promoted to array(double) and double type respectively.</b>
          </th>
        </tr>
        <tr>
          <th>
            <b>SELECT <br> array_contains(array(1), '1');</b>
          </th>
          <th>
            <b>true</b>
          </th>
          <th>
            <b>AnalysisException is thrown since integer type can not be promoted to string type in a loss-less manner.</b>
          </th>
          <th>
            <b>Users can use explict cast</b>
          </th>
        </tr>
        <tr>
          <th>
            <b>SELECT <br> array_contains(array(1), 'anystring');</b>
          </th>
          <th>
            <b>null</b>
          </th>
          <th>
            <b>AnalysisException is thrown since integer type can not be promoted to string type in a loss-less manner.</b>
          </th>
          <th>
            <b>Users can use explict cast</b>
          </th>
        </tr>
  </table>

  - Since Spark 2.4, when there is a struct field in front of the IN operator before a subquery, the inner query must contain a struct field as well. In previous versions, instead, the fields of the struct were compared to the output of the inner query. Eg. if `a` is a `struct(a string, b int)`, in Spark 2.4 `a in (select (1 as a, 'a' as b) from range(1))` is a valid query, while `a in (select 1, 'a' from range(1))` is not. In previous version it was the opposite.
  - In versions 2.2.1+ and 2.3, if `spark.sql.caseSensitive` is set to true, then the `CURRENT_DATE` and `CURRENT_TIMESTAMP` functions incorrectly became case-sensitive and would resolve to columns (unless typed in lower case). In Spark 2.4 this has been fixed and the functions are no longer case-sensitive.
  - Since Spark 2.4, Spark will evaluate the set operations referenced in a query by following a precedence rule as per the SQL standard. If the order is not specified by parentheses, set operations are performed from left to right with the exception that all INTERSECT operations are performed before any UNION, EXCEPT or MINUS operations. The old behaviour of giving equal precedence to all the set operations are preserved under a newly added configuration `spark.sql.legacy.setopsPrecedence.enabled` with a default value of `false`. When this property is set to `true`, spark will evaluate the set operators from left to right as they appear in the query given no explicit ordering is enforced by usage of parenthesis.
  - Since Spark 2.4, Spark will display table description column Last Access value as UNKNOWN when the value was Jan 01 1970.
  - Since Spark 2.4, Spark maximizes the usage of a vectorized ORC reader for ORC files by default. To do that, `spark.sql.orc.impl` and `spark.sql.orc.filterPushdown` change their default values to `native` and `true` respectively.
  - In PySpark, when Arrow optimization is enabled, previously `toPandas` just failed when Arrow optimization is unable to be used whereas `createDataFrame` from Pandas DataFrame allowed the fallback to non-optimization. Now, both `toPandas` and `createDataFrame` from Pandas DataFrame allow the fallback by default, which can be switched off by `spark.sql.execution.arrow.fallback.enabled`.
  - Since Spark 2.4, writing an empty dataframe to a directory launches at least one write task, even if physically the dataframe has no partition. This introduces a small behavior change that for self-describing file formats like Parquet and Orc, Spark creates a metadata-only file in the target directory when writing a 0-partition dataframe, so that schema inference can still work if users read that directory later. The new behavior is more reasonable and more consistent regarding writing empty dataframe.
  - Since Spark 2.4, expression IDs in UDF arguments do not appear in column names. For example, an column name in Spark 2.4 is not `UDF:f(col0 AS colA#28)` but ``UDF:f(col0 AS `colA`)``.
  - Since Spark 2.4, writing a dataframe with an empty or nested empty schema using any file formats (parquet, orc, json, text, csv etc.) is not allowed. An exception is thrown when attempting to write dataframes with empty schema.
  - Since Spark 2.4, Spark compares a DATE type with a TIMESTAMP type after promotes both sides to TIMESTAMP. To set `false` to `spark.sql.legacy.compareDateTimestampInTimestamp` restores the previous behavior. This option will be removed in Spark 3.0.
  - Since Spark 2.4, creating a managed table with nonempty location is not allowed. An exception is thrown when attempting to create a managed table with nonempty location. To set `true` to `spark.sql.legacy.allowCreatingManagedTableUsingNonemptyLocation` restores the previous behavior. This option will be removed in Spark 3.0.
  - Since Spark 2.4, renaming a managed table to existing location is not allowed. An exception is thrown when attempting to rename a managed table to existing location.
  - Since Spark 2.4, the type coercion rules can automatically promote the argument types of the variadic SQL functions (e.g., IN/COALESCE) to the widest common type, no matter how the input arguments order. In prior Spark versions, the promotion could fail in some specific orders (e.g., TimestampType, IntegerType and StringType) and throw an exception.
  - Since Spark 2.4, Spark has enabled non-cascading SQL cache invalidation in addition to the traditional cache invalidation mechanism. The non-cascading cache invalidation mechanism allows users to remove a cache without impacting its dependent caches. This new cache invalidation mechanism is used in scenarios where the data of the cache to be removed is still valid, e.g., calling unpersist() on a Dataset, or dropping a temporary view. This allows users to free up memory and keep the desired caches valid at the same time.
  - In version 2.3 and earlier, Spark converts Parquet Hive tables by default but ignores table properties like `TBLPROPERTIES (parquet.compression 'NONE')`. This happens for ORC Hive table properties like `TBLPROPERTIES (orc.compress 'NONE')` in case of `spark.sql.hive.convertMetastoreOrc=true`, too. Since Spark 2.4, Spark respects Parquet/ORC specific table properties while converting Parquet/ORC Hive tables. As an example, `CREATE TABLE t(id int) STORED AS PARQUET TBLPROPERTIES (parquet.compression 'NONE')` would generate Snappy parquet files during insertion in Spark 2.3, and in Spark 2.4, the result would be uncompressed parquet files.
  - Since Spark 2.0, Spark converts Parquet Hive tables by default for better performance. Since Spark 2.4, Spark converts ORC Hive tables by default, too. It means Spark uses its own ORC support by default instead of Hive SerDe. As an example, `CREATE TABLE t(id int) STORED AS ORC` would be handled with Hive SerDe in Spark 2.3, and in Spark 2.4, it would be converted into Spark's ORC data source table and ORC vectorization would be applied. To set `false` to `spark.sql.hive.convertMetastoreOrc` restores the previous behavior.
  - In version 2.3 and earlier, CSV rows are considered as malformed if at least one column value in the row is malformed. CSV parser dropped such rows in the DROPMALFORMED mode or outputs an error in the FAILFAST mode. Since Spark 2.4, CSV row is considered as malformed only when it contains malformed column values requested from CSV datasource, other values can be ignored. As an example, CSV file contains the "id,name" header and one row "1234". In Spark 2.4, selection of the id column consists of a row with one column value 1234 but in Spark 2.3 and earlier it is empty in the DROPMALFORMED mode. To restore the previous behavior, set `spark.sql.csv.parser.columnPruning.enabled` to `false`.
  - Since Spark 2.4, File listing for compute statistics is done in parallel by default. This can be disabled by setting `spark.sql.statistics.parallelFileListingInStatsComputation.enabled` to `False`.
  - Since Spark 2.4, Metadata files (e.g. Parquet summary files) and temporary files are not counted as data files when calculating table size during Statistics computation.
  - Since Spark 2.4, empty strings are saved as quoted empty strings `""`. In version 2.3 and earlier, empty strings are equal to `null` values and do not reflect to any characters in saved CSV files. For example, the row of `"a", null, "", 1` was writted as `a,,,1`. Since Spark 2.4, the same row is saved as `a,,"",1`. To restore the previous behavior, set the CSV option `emptyValue` to empty (not quoted) string.  
  - Since Spark 2.4, The LOAD DATA command supports wildcard `?` and `*`, which match any one character, and zero or more characters, respectively. Example: `LOAD DATA INPATH '/tmp/folder*/'` or `LOAD DATA INPATH '/tmp/part-?'`. Special Characters like `space` also now work in paths. Example: `LOAD DATA INPATH '/tmp/folder name/'`.

## Upgrading From Spark SQL 2.3.0 to 2.3.1 and above

  - As of version 2.3.1 Arrow functionality, including `pandas_udf` and `toPandas()`/`createDataFrame()` with `spark.sql.execution.arrow.enabled` set to `True`, has been marked as experimental. These are still evolving and not currently recommended for use in production.

## Upgrading From Spark SQL 2.2 to 2.3

  - Since Spark 2.3, the queries from raw JSON/CSV files are disallowed when the referenced columns only include the internal corrupt record column (named `_corrupt_record` by default). For example, `spark.read.schema(schema).json(file).filter($"_corrupt_record".isNotNull).count()` and `spark.read.schema(schema).json(file).select("_corrupt_record").show()`. Instead, you can cache or save the parsed results and then send the same query. For example, `val df = spark.read.schema(schema).json(file).cache()` and then `df.filter($"_corrupt_record".isNotNull).count()`.
  - The `percentile_approx` function previously accepted numeric type input and output double type results. Now it supports date type, timestamp type and numeric types as input types. The result type is also changed to be the same as the input type, which is more reasonable for percentiles.
  - Since Spark 2.3, the Join/Filter's deterministic predicates that are after the first non-deterministic predicates are also pushed down/through the child operators, if possible. In prior Spark versions, these filters are not eligible for predicate pushdown.
  - Partition column inference previously found incorrect common type for different inferred types, for example, previously it ended up with double type as the common type for double type and date type. Now it finds the correct common type for such conflicts. The conflict resolution follows the table below:
    <table class="table">
      <tr>
        <th>
          <b>InputA \ InputB</b>
        </th>
        <th>
          <b>NullType</b>
        </th>
        <th>
          <b>IntegerType</b>
        </th>
        <th>
          <b>LongType</b>
        </th>
        <th>
          <b>DecimalType(38,0)*</b>
        </th>
        <th>
          <b>DoubleType</b>
        </th>
        <th>
          <b>DateType</b>
        </th>
        <th>
          <b>TimestampType</b>
        </th>
        <th>
          <b>StringType</b>
        </th>
      </tr>
      <tr>
        <td>
          <b>NullType</b>
        </td>
        <td>NullType</td>
        <td>IntegerType</td>
        <td>LongType</td>
        <td>DecimalType(38,0)</td>
        <td>DoubleType</td>
        <td>DateType</td>
        <td>TimestampType</td>
        <td>StringType</td>
      </tr>
      <tr>
        <td>
          <b>IntegerType</b>
        </td>
        <td>IntegerType</td>
        <td>IntegerType</td>
        <td>LongType</td>
        <td>DecimalType(38,0)</td>
        <td>DoubleType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
      </tr>
      <tr>
        <td>
          <b>LongType</b>
        </td>
        <td>LongType</td>
        <td>LongType</td>
        <td>LongType</td>
        <td>DecimalType(38,0)</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
      </tr>
      <tr>
        <td>
          <b>DecimalType(38,0)*</b>
        </td>
        <td>DecimalType(38,0)</td>
        <td>DecimalType(38,0)</td>
        <td>DecimalType(38,0)</td>
        <td>DecimalType(38,0)</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
      </tr>
      <tr>
        <td>
          <b>DoubleType</b>
        </td>
        <td>DoubleType</td>
        <td>DoubleType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>DoubleType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
      </tr>
      <tr>
        <td>
          <b>DateType</b>
        </td>
        <td>DateType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>DateType</td>
        <td>TimestampType</td>
        <td>StringType</td>
      </tr>
      <tr>
        <td>
          <b>TimestampType</b>
        </td>
        <td>TimestampType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>TimestampType</td>
        <td>TimestampType</td>
        <td>StringType</td>
      </tr>
      <tr>
        <td>
          <b>StringType</b>
        </td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
        <td>StringType</td>
      </tr>
    </table>

    Note that, for <b>DecimalType(38,0)*</b>, the table above intentionally does not cover all other combinations of scales and precisions because currently we only infer decimal type like `BigInteger`/`BigInt`. For example, 1.1 is inferred as double type.
  - In PySpark, now we need Pandas 0.19.2 or upper if you want to use Pandas related functionalities, such as `toPandas`, `createDataFrame` from Pandas DataFrame, etc.
  - In PySpark, the behavior of timestamp values for Pandas related functionalities was changed to respect session timezone. If you want to use the old behavior, you need to set a configuration `spark.sql.execution.pandas.respectSessionTimeZone` to `False`. See [SPARK-22395](https://issues.apache.org/jira/browse/SPARK-22395) for details.
  - In PySpark, `na.fill()` or `fillna` also accepts boolean and replaces nulls with booleans. In prior Spark versions, PySpark just ignores it and returns the original Dataset/DataFrame.
  - Since Spark 2.3, when either broadcast hash join or broadcast nested loop join is applicable, we prefer to broadcasting the table that is explicitly specified in a broadcast hint. For details, see the section [Broadcast Hint](#broadcast-hint-for-sql-queries) and [SPARK-22489](https://issues.apache.org/jira/browse/SPARK-22489).
  - Since Spark 2.3, when all inputs are binary, `functions.concat()` returns an output as binary. Otherwise, it returns as a string. Until Spark 2.3, it always returns as a string despite of input types. To keep the old behavior, set `spark.sql.function.concatBinaryAsString` to `true`.
  - Since Spark 2.3, when all inputs are binary, SQL `elt()` returns an output as binary. Otherwise, it returns as a string. Until Spark 2.3, it always returns as a string despite of input types. To keep the old behavior, set `spark.sql.function.eltOutputAsString` to `true`.

 - Since Spark 2.3, by default arithmetic operations between decimals return a rounded value if an exact representation is not possible (instead of returning NULL). This is compliant with SQL ANSI 2011 specification and Hive's new behavior introduced in Hive 2.2 (HIVE-15331). This involves the following changes
    - The rules to determine the result type of an arithmetic operation have been updated. In particular, if the precision / scale needed are out of the range of available values, the scale is reduced up to 6, in order to prevent the truncation of the integer part of the decimals. All the arithmetic operations are affected by the change, ie. addition (`+`), subtraction (`-`), multiplication (`*`), division (`/`), remainder (`%`) and positive module (`pmod`).
    - Literal values used in SQL operations are converted to DECIMAL with the exact precision and scale needed by them.
    - The configuration `spark.sql.decimalOperations.allowPrecisionLoss` has been introduced. It defaults to `true`, which means the new behavior described here; if set to `false`, Spark uses previous rules, ie. it doesn't adjust the needed scale to represent the values and it returns NULL if an exact representation of the value is not possible.
  - In PySpark, `df.replace` does not allow to omit `value` when `to_replace` is not a dictionary. Previously, `value` could be omitted in the other cases and had `None` by default, which is counterintuitive and error-prone.
  - Un-aliased subquery's semantic has not been well defined with confusing behaviors. Since Spark 2.3, we invalidate such confusing cases, for example: `SELECT v.i from (SELECT i FROM v)`, Spark will throw an analysis exception in this case because users should not be able to use the qualifier inside a subquery. See [SPARK-20690](https://issues.apache.org/jira/browse/SPARK-20690) and [SPARK-21335](https://issues.apache.org/jira/browse/SPARK-21335) for more details.

## Upgrading From Spark SQL 2.1 to 2.2

  - Spark 2.1.1 introduced a new configuration key: `spark.sql.hive.caseSensitiveInferenceMode`. It had a default setting of `NEVER_INFER`, which kept behavior identical to 2.1.0. However, Spark 2.2.0 changes this setting's default value to `INFER_AND_SAVE` to restore compatibility with reading Hive metastore tables whose underlying file schema have mixed-case column names. With the `INFER_AND_SAVE` configuration value, on first access Spark will perform schema inference on any Hive metastore table for which it has not already saved an inferred schema. Note that schema inference can be a very time-consuming operation for tables with thousands of partitions. If compatibility with mixed-case column names is not a concern, you can safely set `spark.sql.hive.caseSensitiveInferenceMode` to `NEVER_INFER` to avoid the initial overhead of schema inference. Note that with the new default `INFER_AND_SAVE` setting, the results of the schema inference are saved as a metastore key for future use. Therefore, the initial schema inference occurs only at a table's first access.
  
  - Since Spark 2.2.1 and 2.3.0, the schema is always inferred at runtime when the data source tables have the columns that exist in both partition schema and data schema. The inferred schema does not have the partitioned columns. When reading the table, Spark respects the partition values of these overlapping columns instead of the values stored in the data source files. In 2.2.0 and 2.1.x release, the inferred schema is partitioned but the data of the table is invisible to users (i.e., the result set is empty).

## Upgrading From Spark SQL 2.0 to 2.1

 - Datasource tables now store partition metadata in the Hive metastore. This means that Hive DDLs such as `ALTER TABLE PARTITION ... SET LOCATION` are now available for tables created with the Datasource API.
    - Legacy datasource tables can be migrated to this format via the `MSCK REPAIR TABLE` command. Migrating legacy tables is recommended to take advantage of Hive DDL support and improved planning performance.
    - To determine if a table has been migrated, look for the `PartitionProvider: Catalog` attribute when issuing `DESCRIBE FORMATTED` on the table.
 - Changes to `INSERT OVERWRITE TABLE ... PARTITION ...` behavior for Datasource tables.
    - In prior Spark versions `INSERT OVERWRITE` overwrote the entire Datasource table, even when given a partition specification. Now only partitions matching the specification are overwritten.
    - Note that this still differs from the behavior of Hive tables, which is to overwrite only partitions overlapping with newly inserted data.

## Upgrading From Spark SQL 1.6 to 2.0

 - `SparkSession` is now the new entry point of Spark that replaces the old `SQLContext` and
   `HiveContext`. Note that the old SQLContext and HiveContext are kept for backward compatibility. A new `catalog` interface is accessible from `SparkSession` - existing API on databases and tables access such as `listTables`, `createExternalTable`, `dropTempView`, `cacheTable` are moved here.

 - Dataset API and DataFrame API are unified. In Scala, `DataFrame` becomes a type alias for
   `Dataset[Row]`, while Java API users must replace `DataFrame` with `Dataset<Row>`. Both the typed
   transformations (e.g., `map`, `filter`, and `groupByKey`) and untyped transformations (e.g.,
   `select` and `groupBy`) are available on the Dataset class. Since compile-time type-safety in
   Python and R is not a language feature, the concept of Dataset does not apply to these languages???
   APIs. Instead, `DataFrame` remains the primary programming abstraction, which is analogous to the
   single-node data frame notion in these languages.

 - Dataset and DataFrame API `unionAll` has been deprecated and replaced by `union`
 - Dataset and DataFrame API `explode` has been deprecated, alternatively, use `functions.explode()` with `select` or `flatMap`
 - Dataset and DataFrame API `registerTempTable` has been deprecated and replaced by `createOrReplaceTempView`

 - Changes to `CREATE TABLE ... LOCATION` behavior for Hive tables.
    - From Spark 2.0, `CREATE TABLE ... LOCATION` is equivalent to `CREATE EXTERNAL TABLE ... LOCATION`
      in order to prevent accidental dropping the existing data in the user-provided locations.
      That means, a Hive table created in Spark SQL with the user-specified location is always a Hive external table.
      Dropping external tables will not remove the data. Users are not allowed to specify the location for Hive managed tables.
      Note that this is different from the Hive behavior.
    - As a result, `DROP TABLE` statements on those tables will not remove the data.

 - `spark.sql.parquet.cacheMetadata` is no longer used.
   See [SPARK-13664](https://issues.apache.org/jira/browse/SPARK-13664) for details.

## Upgrading From Spark SQL 1.5 to 1.6

 - From Spark 1.6, by default, the Thrift server runs in multi-session mode. Which means each JDBC/ODBC
   connection owns a copy of their own SQL configuration and temporary function registry. Cached
   tables are still shared though. If you prefer to run the Thrift server in the old single-session
   mode, please set option `spark.sql.hive.thriftServer.singleSession` to `true`. You may either add
   this option to `spark-defaults.conf`, or pass it to `start-thriftserver.sh` via `--conf`:

   {% highlight bash %}
   ./sbin/start-thriftserver.sh \
     --conf spark.sql.hive.thriftServer.singleSession=true \
     ...
   {% endhighlight %}
 - Since 1.6.1, withColumn method in sparkR supports adding a new column to or replacing existing columns
   of the same name of a DataFrame.

 - From Spark 1.6, LongType casts to TimestampType expect seconds instead of microseconds. This
   change was made to match the behavior of Hive 1.2 for more consistent type casting to TimestampType
   from numeric types. See [SPARK-11724](https://issues.apache.org/jira/browse/SPARK-11724) for
   details.

## Upgrading From Spark SQL 1.4 to 1.5

 - Optimized execution using manually managed memory (Tungsten) is now enabled by default, along with
   code generation for expression evaluation. These features can both be disabled by setting
   `spark.sql.tungsten.enabled` to `false`.
 - Parquet schema merging is no longer enabled by default. It can be re-enabled by setting
   `spark.sql.parquet.mergeSchema` to `true`.
 - Resolution of strings to columns in python now supports using dots (`.`) to qualify the column or
   access nested values. For example `df['table.column.nestedField']`. However, this means that if
   your column name contains any dots you must now escape them using backticks (e.g., ``table.`column.with.dots`.nested``).
 - In-memory columnar storage partition pruning is on by default. It can be disabled by setting
   `spark.sql.inMemoryColumnarStorage.partitionPruning` to `false`.
 - Unlimited precision decimal columns are no longer supported, instead Spark SQL enforces a maximum
   precision of 38. When inferring schema from `BigDecimal` objects, a precision of (38, 18) is now
   used. When no precision is specified in DDL then the default remains `Decimal(10, 0)`.
 - Timestamps are now stored at a precision of 1us, rather than 1ns
 - In the `sql` dialect, floating point numbers are now parsed as decimal. HiveQL parsing remains
   unchanged.
 - The canonical name of SQL/DataFrame functions are now lower case (e.g., sum vs SUM).
 - JSON data source will not automatically load new files that are created by other applications
   (i.e. files that are not inserted to the dataset through Spark SQL).
   For a JSON persistent table (i.e. the metadata of the table is stored in Hive Metastore),
   users can use `REFRESH TABLE` SQL command or `HiveContext`'s `refreshTable` method
   to include those new files to the table. For a DataFrame representing a JSON dataset, users need to recreate
   the DataFrame and the new DataFrame will include new files.
 - DataFrame.withColumn method in pySpark supports adding a new column or replacing existing columns of the same name.

## Upgrading from Spark SQL 1.3 to 1.4

#### DataFrame data reader/writer interface

Based on user feedback, we created a new, more fluid API for reading data in (`SQLContext.read`)
and writing data out (`DataFrame.write`),
and deprecated the old APIs (e.g., `SQLContext.parquetFile`, `SQLContext.jsonFile`).

See the API docs for `SQLContext.read` (
  <a href="api/scala/index.html#org.apache.spark.sql.SQLContext@read:DataFrameReader">Scala</a>,
  <a href="api/java/org/apache/spark/sql/SQLContext.html#read()">Java</a>,
  <a href="api/python/pyspark.sql.html#pyspark.sql.SQLContext.read">Python</a>
) and `DataFrame.write` (
  <a href="api/scala/index.html#org.apache.spark.sql.DataFrame@write:DataFrameWriter">Scala</a>,
  <a href="api/java/org/apache/spark/sql/Dataset.html#write()">Java</a>,
  <a href="api/python/pyspark.sql.html#pyspark.sql.DataFrame.write">Python</a>
) more information.


#### DataFrame.groupBy retains grouping columns

Based on user feedback, we changed the default behavior of `DataFrame.groupBy().agg()` to retain the
grouping columns in the resulting `DataFrame`. To keep the behavior in 1.3, set `spark.sql.retainGroupColumns` to `false`.

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% highlight scala %}

// In 1.3.x, in order for the grouping column "department" to show up,
// it must be included explicitly as part of the agg function call.
df.groupBy("department").agg($"department", max("age"), sum("expense"))

// In 1.4+, grouping column "department" is included automatically.
df.groupBy("department").agg(max("age"), sum("expense"))

// Revert to 1.3 behavior (not retaining grouping column) by:
sqlContext.setConf("spark.sql.retainGroupColumns", "false")

{% endhighlight %}
</div>

<div data-lang="java"  markdown="1">
{% highlight java %}

// In 1.3.x, in order for the grouping column "department" to show up,
// it must be included explicitly as part of the agg function call.
df.groupBy("department").agg(col("department"), max("age"), sum("expense"));

// In 1.4+, grouping column "department" is included automatically.
df.groupBy("department").agg(max("age"), sum("expense"));

// Revert to 1.3 behavior (not retaining grouping column) by:
sqlContext.setConf("spark.sql.retainGroupColumns", "false");

{% endhighlight %}
</div>

<div data-lang="python"  markdown="1">
{% highlight python %}

import pyspark.sql.functions as func

# In 1.3.x, in order for the grouping column "department" to show up,
# it must be included explicitly as part of the agg function call.
df.groupBy("department").agg(df["department"], func.max("age"), func.sum("expense"))

# In 1.4+, grouping column "department" is included automatically.
df.groupBy("department").agg(func.max("age"), func.sum("expense"))

# Revert to 1.3.x behavior (not retaining grouping column) by:
sqlContext.setConf("spark.sql.retainGroupColumns", "false")

{% endhighlight %}
</div>

</div>


#### Behavior change on DataFrame.withColumn

Prior to 1.4, DataFrame.withColumn() supports adding a column only. The column will always be added
as a new column with its specified name in the result DataFrame even if there may be any existing
columns of the same name. Since 1.4, DataFrame.withColumn() supports adding a column of a different
name from names of all existing columns or replacing existing columns of the same name.

Note that this change is only for Scala API, not for PySpark and SparkR.


## Upgrading from Spark SQL 1.0-1.2 to 1.3

In Spark 1.3 we removed the "Alpha" label from Spark SQL and as part of this did a cleanup of the
available APIs. From Spark 1.3 onwards, Spark SQL will provide binary compatibility with other
releases in the 1.X series. This compatibility guarantee excludes APIs that are explicitly marked
as unstable (i.e., DeveloperAPI or Experimental).

#### Rename of SchemaRDD to DataFrame

The largest change that users will notice when upgrading to Spark SQL 1.3 is that `SchemaRDD` has
been renamed to `DataFrame`. This is primarily because DataFrames no longer inherit from RDD
directly, but instead provide most of the functionality that RDDs provide though their own
implementation. DataFrames can still be converted to RDDs by calling the `.rdd` method.

In Scala, there is a type alias from `SchemaRDD` to `DataFrame` to provide source compatibility for
some use cases. It is still recommended that users update their code to use `DataFrame` instead.
Java and Python users will need to update their code.

#### Unification of the Java and Scala APIs

Prior to Spark 1.3 there were separate Java compatible classes (`JavaSQLContext` and `JavaSchemaRDD`)
that mirrored the Scala API. In Spark 1.3 the Java API and Scala API have been unified. Users
of either language should use `SQLContext` and `DataFrame`. In general these classes try to
use types that are usable from both languages (i.e. `Array` instead of language-specific collections).
In some cases where no common type exists (e.g., for passing in closures or Maps) function overloading
is used instead.

Additionally, the Java specific types API has been removed. Users of both Scala and Java should
use the classes present in `org.apache.spark.sql.types` to describe schema programmatically.


#### Isolation of Implicit Conversions and Removal of dsl Package (Scala-only)

Many of the code examples prior to Spark 1.3 started with `import sqlContext._`, which brought
all of the functions from sqlContext into scope. In Spark 1.3 we have isolated the implicit
conversions for converting `RDD`s into `DataFrame`s into an object inside of the `SQLContext`.
Users should now write `import sqlContext.implicits._`.

Additionally, the implicit conversions now only augment RDDs that are composed of `Product`s (i.e.,
case classes or tuples) with a method `toDF`, instead of applying automatically.

When using function inside of the DSL (now replaced with the `DataFrame` API) users used to import
`org.apache.spark.sql.catalyst.dsl`. Instead the public dataframe functions API should be used:
`import org.apache.spark.sql.functions._`.

#### Removal of the type aliases in org.apache.spark.sql for DataType (Scala-only)

Spark 1.3 removes the type aliases that were present in the base sql package for `DataType`. Users
should instead import the classes in `org.apache.spark.sql.types`

#### UDF Registration Moved to `sqlContext.udf` (Java & Scala)

Functions that are used to register UDFs, either for use in the DataFrame DSL or SQL, have been
moved into the udf object in `SQLContext`.

<div class="codetabs">
<div data-lang="scala"  markdown="1">
{% highlight scala %}

sqlContext.udf.register("strLen", (s: String) => s.length())

{% endhighlight %}
</div>

<div data-lang="java"  markdown="1">
{% highlight java %}

sqlContext.udf().register("strLen", (String s) -> s.length(), DataTypes.IntegerType);

{% endhighlight %}
</div>

</div>

Python UDF registration is unchanged.

#### Python DataTypes No Longer Singletons

When using DataTypes in Python you will need to construct them (i.e. `StringType()`) instead of
referencing a singleton.

## Compatibility with Apache Hive

Spark SQL is designed to be compatible with the Hive Metastore, SerDes and UDFs.
Currently, Hive SerDes and UDFs are based on Hive 1.2.1,
and Spark SQL can be connected to different versions of Hive Metastore
(from 0.12.0 to 2.3.3. Also see [Interacting with Different Versions of Hive Metastore](#interacting-with-different-versions-of-hive-metastore)).

#### Deploying in Existing Hive Warehouses

The Spark SQL Thrift JDBC server is designed to be "out of the box" compatible with existing Hive
installations. You do not need to modify your existing Hive Metastore or change the data placement
or partitioning of your tables.

### Supported Hive Features

Spark SQL supports the vast majority of Hive features, such as:

* Hive query statements, including:
  * `SELECT`
  * `GROUP BY`
  * `ORDER BY`
  * `CLUSTER BY`
  * `SORT BY`
* All Hive operators, including:
  * Relational operators (`=`, `???`, `==`, `<>`, `<`, `>`, `>=`, `<=`, etc)
  * Arithmetic operators (`+`, `-`, `*`, `/`, `%`, etc)
  * Logical operators (`AND`, `&&`, `OR`, `||`, etc)
  * Complex type constructors
  * Mathematical functions (`sign`, `ln`, `cos`, etc)
  * String functions (`instr`, `length`, `printf`, etc)
* User defined functions (UDF)
* User defined aggregation functions (UDAF)
* User defined serialization formats (SerDes)
* Window functions
* Joins
  * `JOIN`
  * `{LEFT|RIGHT|FULL} OUTER JOIN`
  * `LEFT SEMI JOIN`
  * `CROSS JOIN`
* Unions
* Sub-queries
  * `SELECT col FROM ( SELECT a + b AS col from t1) t2`
* Sampling
* Explain
* Partitioned tables including dynamic partition insertion
* View
* All Hive DDL Functions, including:
  * `CREATE TABLE`
  * `CREATE TABLE AS SELECT`
  * `ALTER TABLE`
* Most Hive Data types, including:
  * `TINYINT`
  * `SMALLINT`
  * `INT`
  * `BIGINT`
  * `BOOLEAN`
  * `FLOAT`
  * `DOUBLE`
  * `STRING`
  * `BINARY`
  * `TIMESTAMP`
  * `DATE`
  * `ARRAY<>`
  * `MAP<>`
  * `STRUCT<>`

### Unsupported Hive Functionality

Below is a list of Hive features that we don't support yet. Most of these features are rarely used
in Hive deployments.

**Major Hive Features**

* Tables with buckets: bucket is the hash partitioning within a Hive table partition. Spark SQL
  doesn't support buckets yet.


**Esoteric Hive Features**

* `UNION` type
* Unique join
* Column statistics collecting: Spark SQL does not piggyback scans to collect column statistics at
  the moment and only supports populating the sizeInBytes field of the hive metastore.

**Hive Input/Output Formats**

* File format for CLI: For results showing back to the CLI, Spark SQL only supports TextOutputFormat.
* Hadoop archive

**Hive Optimizations**

A handful of Hive optimizations are not yet included in Spark. Some of these (such as indexes) are
less important due to Spark SQL's in-memory computational model. Others are slotted for future
releases of Spark SQL.

* Block-level bitmap indexes and virtual columns (used to build indexes)
* Automatically determine the number of reducers for joins and groupbys: Currently, in Spark SQL, you
  need to control the degree of parallelism post-shuffle using "`SET spark.sql.shuffle.partitions=[num_tasks];`".
* Meta-data only query: For queries that can be answered by using only metadata, Spark SQL still
  launches tasks to compute the result.
* Skew data flag: Spark SQL does not follow the skew data flags in Hive.
* `STREAMTABLE` hint in join: Spark SQL does not follow the `STREAMTABLE` hint.
* Merge multiple small files for query results: if the result output contains multiple small files,
  Hive can optionally merge the small files into fewer large files to avoid overflowing the HDFS
  metadata. Spark SQL does not support that.

**Hive UDF/UDTF/UDAF**

Not all the APIs of the Hive UDF/UDTF/UDAF are supported by Spark SQL. Below are the unsupported APIs:

* `getRequiredJars` and `getRequiredFiles` (`UDF` and `GenericUDF`) are functions to automatically
  include additional resources required by this UDF.
* `initialize(StructObjectInspector)` in `GenericUDTF` is not supported yet. Spark SQL currently uses
  a deprecated interface `initialize(ObjectInspector[])` only.
* `configure` (`GenericUDF`, `GenericUDTF`, and `GenericUDAFEvaluator`) is a function to initialize
  functions with `MapredContext`, which is inapplicable to Spark.
* `close` (`GenericUDF` and `GenericUDAFEvaluator`) is a function to release associated resources.
  Spark SQL does not call this function when tasks finish.
* `reset` (`GenericUDAFEvaluator`) is a function to re-initialize aggregation for reusing the same aggregation.
  Spark SQL currently does not support the reuse of aggregation.
* `getWindowingEvaluator` (`GenericUDAFEvaluator`) is a function to optimize aggregation by evaluating
  an aggregate over a fixed window.

### Incompatible Hive UDF

Below are the scenarios in which Hive and Spark generate different results:

* `SQRT(n)` If n < 0, Hive returns null, Spark SQL returns NaN.
* `ACOS(n)` If n < -1 or n > 1, Hive returns null, Spark SQL returns NaN.
* `ASIN(n)` If n < -1 or n > 1, Hive returns null, Spark SQL returns NaN.

# Reference

## Data Types

Spark SQL and DataFrames support the following data types:

* Numeric types
    - `ByteType`: Represents 1-byte signed integer numbers.
    The range of numbers is from `-128` to `127`.
    - `ShortType`: Represents 2-byte signed integer numbers.
    The range of numbers is from `-32768` to `32767`.
    - `IntegerType`: Represents 4-byte signed integer numbers.
    The range of numbers is from `-2147483648` to `2147483647`.
    - `LongType`: Represents 8-byte signed integer numbers.
    The range of numbers is from `-9223372036854775808` to `9223372036854775807`.
    - `FloatType`: Represents 4-byte single-precision floating point numbers.
    - `DoubleType`: Represents 8-byte double-precision floating point numbers.
    - `DecimalType`: Represents arbitrary-precision signed decimal numbers. Backed internally by `java.math.BigDecimal`. A `BigDecimal` consists of an arbitrary precision integer unscaled value and a 32-bit integer scale.
* String type
    - `StringType`: Represents character string values.
* Binary type
    - `BinaryType`: Represents byte sequence values.
* Boolean type
    - `BooleanType`: Represents boolean values.
* Datetime type
    - `TimestampType`: Represents values comprising values of fields year, month, day,
    hour, minute, and second.
    - `DateType`: Represents values comprising values of fields year, month, day.
* Complex types
    - `ArrayType(elementType, containsNull)`: Represents values comprising a sequence of
    elements with the type of `elementType`. `containsNull` is used to indicate if
    elements in a `ArrayType` value can have `null` values.
    - `MapType(keyType, valueType, valueContainsNull)`:
    Represents values comprising a set of key-value pairs. The data type of keys are
    described by `keyType` and the data type of values are described by `valueType`.
    For a `MapType` value, keys are not allowed to have `null` values. `valueContainsNull`
    is used to indicate if values of a `MapType` value can have `null` values.
    - `StructType(fields)`: Represents values with the structure described by
    a sequence of `StructField`s (`fields`).
        * `StructField(name, dataType, nullable)`: Represents a field in a `StructType`.
        The name of a field is indicated by `name`. The data type of a field is indicated
        by `dataType`. `nullable` is used to indicate if values of this fields can have
        `null` values.

<div class="codetabs">
<div data-lang="scala"  markdown="1">

All data types of Spark SQL are located in the package `org.apache.spark.sql.types`.
You can access them by doing

{% include_example data_types scala/org/apache/spark/examples/sql/SparkSQLExample.scala %}

<table class="table">
<tr>
  <th style="width:20%">Data type</th>
  <th style="width:40%">Value type in Scala</th>
  <th>API to access or create a data type</th></tr>
<tr>
  <td> <b>ByteType</b> </td>
  <td> Byte </td>
  <td>
  ByteType
  </td>
</tr>
<tr>
  <td> <b>ShortType</b> </td>
  <td> Short </td>
  <td>
  ShortType
  </td>
</tr>
<tr>
  <td> <b>IntegerType</b> </td>
  <td> Int </td>
  <td>
  IntegerType
  </td>
</tr>
<tr>
  <td> <b>LongType</b> </td>
  <td> Long </td>
  <td>
  LongType
  </td>
</tr>
<tr>
  <td> <b>FloatType</b> </td>
  <td> Float </td>
  <td>
  FloatType
  </td>
</tr>
<tr>
  <td> <b>DoubleType</b> </td>
  <td> Double </td>
  <td>
  DoubleType
  </td>
</tr>
<tr>
  <td> <b>DecimalType</b> </td>
  <td> java.math.BigDecimal </td>
  <td>
  DecimalType
  </td>
</tr>
<tr>
  <td> <b>StringType</b> </td>
  <td> String </td>
  <td>
  StringType
  </td>
</tr>
<tr>
  <td> <b>BinaryType</b> </td>
  <td> Array[Byte] </td>
  <td>
  BinaryType
  </td>
</tr>
<tr>
  <td> <b>BooleanType</b> </td>
  <td> Boolean </td>
  <td>
  BooleanType
  </td>
</tr>
<tr>
  <td> <b>TimestampType</b> </td>
  <td> java.sql.Timestamp </td>
  <td>
  TimestampType
  </td>
</tr>
<tr>
  <td> <b>DateType</b> </td>
  <td> java.sql.Date </td>
  <td>
  DateType
  </td>
</tr>
<tr>
  <td> <b>ArrayType</b> </td>
  <td> scala.collection.Seq </td>
  <td>
  ArrayType(<i>elementType</i>, [<i>containsNull</i>])<br />
  <b>Note:</b> The default value of <i>containsNull</i> is <i>true</i>.
  </td>
</tr>
<tr>
  <td> <b>MapType</b> </td>
  <td> scala.collection.Map </td>
  <td>
  MapType(<i>keyType</i>, <i>valueType</i>, [<i>valueContainsNull</i>])<br />
  <b>Note:</b> The default value of <i>valueContainsNull</i> is <i>true</i>.
  </td>
</tr>
<tr>
  <td> <b>StructType</b> </td>
  <td> org.apache.spark.sql.Row </td>
  <td>
  StructType(<i>fields</i>)<br />
  <b>Note:</b> <i>fields</i> is a Seq of StructFields. Also, two fields with the same
  name are not allowed.
  </td>
</tr>
<tr>
  <td> <b>StructField</b> </td>
  <td> The value type in Scala of the data type of this field
  (For example, Int for a StructField with the data type IntegerType) </td>
  <td>
  StructField(<i>name</i>, <i>dataType</i>, [<i>nullable</i>])<br />
  <b>Note:</b> The default value of <i>nullable</i> is <i>true</i>.
  </td>
</tr>
</table>

</div>

<div data-lang="java" markdown="1">

All data types of Spark SQL are located in the package of
`org.apache.spark.sql.types`. To access or create a data type,
please use factory methods provided in
`org.apache.spark.sql.types.DataTypes`.

<table class="table">
<tr>
  <th style="width:20%">Data type</th>
  <th style="width:40%">Value type in Java</th>
  <th>API to access or create a data type</th></tr>
<tr>
  <td> <b>ByteType</b> </td>
  <td> byte or Byte </td>
  <td>
  DataTypes.ByteType
  </td>
</tr>
<tr>
  <td> <b>ShortType</b> </td>
  <td> short or Short </td>
  <td>
  DataTypes.ShortType
  </td>
</tr>
<tr>
  <td> <b>IntegerType</b> </td>
  <td> int or Integer </td>
  <td>
  DataTypes.IntegerType
  </td>
</tr>
<tr>
  <td> <b>LongType</b> </td>
  <td> long or Long </td>
  <td>
  DataTypes.LongType
  </td>
</tr>
<tr>
  <td> <b>FloatType</b> </td>
  <td> float or Float </td>
  <td>
  DataTypes.FloatType
  </td>
</tr>
<tr>
  <td> <b>DoubleType</b> </td>
  <td> double or Double </td>
  <td>
  DataTypes.DoubleType
  </td>
</tr>
<tr>
  <td> <b>DecimalType</b> </td>
  <td> java.math.BigDecimal </td>
  <td>
  DataTypes.createDecimalType()<br />
  DataTypes.createDecimalType(<i>precision</i>, <i>scale</i>).
  </td>
</tr>
<tr>
  <td> <b>StringType</b> </td>
  <td> String </td>
  <td>
  DataTypes.StringType
  </td>
</tr>
<tr>
  <td> <b>BinaryType</b> </td>
  <td> byte[] </td>
  <td>
  DataTypes.BinaryType
  </td>
</tr>
<tr>
  <td> <b>BooleanType</b> </td>
  <td> boolean or Boolean </td>
  <td>
  DataTypes.BooleanType
  </td>
</tr>
<tr>
  <td> <b>TimestampType</b> </td>
  <td> java.sql.Timestamp </td>
  <td>
  DataTypes.TimestampType
  </td>
</tr>
<tr>
  <td> <b>DateType</b> </td>
  <td> java.sql.Date </td>
  <td>
  DataTypes.DateType
  </td>
</tr>
<tr>
  <td> <b>ArrayType</b> </td>
  <td> java.util.List </td>
  <td>
  DataTypes.createArrayType(<i>elementType</i>)<br />
  <b>Note:</b> The value of <i>containsNull</i> will be <i>true</i><br />
  DataTypes.createArrayType(<i>elementType</i>, <i>containsNull</i>).
  </td>
</tr>
<tr>
  <td> <b>MapType</b> </td>
  <td> java.util.Map </td>
  <td>
  DataTypes.createMapType(<i>keyType</i>, <i>valueType</i>)<br />
  <b>Note:</b> The value of <i>valueContainsNull</i> will be <i>true</i>.<br />
  DataTypes.createMapType(<i>keyType</i>, <i>valueType</i>, <i>valueContainsNull</i>)<br />
  </td>
</tr>
<tr>
  <td> <b>StructType</b> </td>
  <td> org.apache.spark.sql.Row </td>
  <td>
  DataTypes.createStructType(<i>fields</i>)<br />
  <b>Note:</b> <i>fields</i> is a List or an array of StructFields.
  Also, two fields with the same name are not allowed.
  </td>
</tr>
<tr>
  <td> <b>StructField</b> </td>
  <td> The value type in Java of the data type of this field
  (For example, int for a StructField with the data type IntegerType) </td>
  <td>
  DataTypes.createStructField(<i>name</i>, <i>dataType</i>, <i>nullable</i>)
  </td>
</tr>
</table>

</div>

<div data-lang="python"  markdown="1">

All data types of Spark SQL are located in the package of `pyspark.sql.types`.
You can access them by doing
{% highlight python %}
from pyspark.sql.types import *
{% endhighlight %}

<table class="table">
<tr>
  <th style="width:20%">Data type</th>
  <th style="width:40%">Value type in Python</th>
  <th>API to access or create a data type</th></tr>
<tr>
  <td> <b>ByteType</b> </td>
  <td>
  int or long <br />
  <b>Note:</b> Numbers will be converted to 1-byte signed integer numbers at runtime.
  Please make sure that numbers are within the range of -128 to 127.
  </td>
  <td>
  ByteType()
  </td>
</tr>
<tr>
  <td> <b>ShortType</b> </td>
  <td>
  int or long <br />
  <b>Note:</b> Numbers will be converted to 2-byte signed integer numbers at runtime.
  Please make sure that numbers are within the range of -32768 to 32767.
  </td>
  <td>
  ShortType()
  </td>
</tr>
<tr>
  <td> <b>IntegerType</b> </td>
  <td> int or long </td>
  <td>
  IntegerType()
  </td>
</tr>
<tr>
  <td> <b>LongType</b> </td>
  <td>
  long <br />
  <b>Note:</b> Numbers will be converted to 8-byte signed integer numbers at runtime.
  Please make sure that numbers are within the range of
  -9223372036854775808 to 9223372036854775807.
  Otherwise, please convert data to decimal.Decimal and use DecimalType.
  </td>
  <td>
  LongType()
  </td>
</tr>
<tr>
  <td> <b>FloatType</b> </td>
  <td>
  float <br />
  <b>Note:</b> Numbers will be converted to 4-byte single-precision floating
  point numbers at runtime.
  </td>
  <td>
  FloatType()
  </td>
</tr>
<tr>
  <td> <b>DoubleType</b> </td>
  <td> float </td>
  <td>
  DoubleType()
  </td>
</tr>
<tr>
  <td> <b>DecimalType</b> </td>
  <td> decimal.Decimal </td>
  <td>
  DecimalType()
  </td>
</tr>
<tr>
  <td> <b>StringType</b> </td>
  <td> string </td>
  <td>
  StringType()
  </td>
</tr>
<tr>
  <td> <b>BinaryType</b> </td>
  <td> bytearray </td>
  <td>
  BinaryType()
  </td>
</tr>
<tr>
  <td> <b>BooleanType</b> </td>
  <td> bool </td>
  <td>
  BooleanType()
  </td>
</tr>
<tr>
  <td> <b>TimestampType</b> </td>
  <td> datetime.datetime </td>
  <td>
  TimestampType()
  </td>
</tr>
<tr>
  <td> <b>DateType</b> </td>
  <td> datetime.date </td>
  <td>
  DateType()
  </td>
</tr>
<tr>
  <td> <b>ArrayType</b> </td>
  <td> list, tuple, or array </td>
  <td>
  ArrayType(<i>elementType</i>, [<i>containsNull</i>])<br />
  <b>Note:</b> The default value of <i>containsNull</i> is <i>True</i>.
  </td>
</tr>
<tr>
  <td> <b>MapType</b> </td>
  <td> dict </td>
  <td>
  MapType(<i>keyType</i>, <i>valueType</i>, [<i>valueContainsNull</i>])<br />
  <b>Note:</b> The default value of <i>valueContainsNull</i> is <i>True</i>.
  </td>
</tr>
<tr>
  <td> <b>StructType</b> </td>
  <td> list or tuple </td>
  <td>
  StructType(<i>fields</i>)<br />
  <b>Note:</b> <i>fields</i> is a Seq of StructFields. Also, two fields with the same
  name are not allowed.
  </td>
</tr>
<tr>
  <td> <b>StructField</b> </td>
  <td> The value type in Python of the data type of this field
  (For example, Int for a StructField with the data type IntegerType) </td>
  <td>
  StructField(<i>name</i>, <i>dataType</i>, [<i>nullable</i>])<br />
  <b>Note:</b> The default value of <i>nullable</i> is <i>True</i>.
  </td>
</tr>
</table>

</div>

<div data-lang="r"  markdown="1">

<table class="table">
<tr>
  <th style="width:20%">Data type</th>
  <th style="width:40%">Value type in R</th>
  <th>API to access or create a data type</th></tr>
<tr>
  <td> <b>ByteType</b> </td>
  <td>
  integer <br />
  <b>Note:</b> Numbers will be converted to 1-byte signed integer numbers at runtime.
  Please make sure that numbers are within the range of -128 to 127.
  </td>
  <td>
  "byte"
  </td>
</tr>
<tr>
  <td> <b>ShortType</b> </td>
  <td>
  integer <br />
  <b>Note:</b> Numbers will be converted to 2-byte signed integer numbers at runtime.
  Please make sure that numbers are within the range of -32768 to 32767.
  </td>
  <td>
  "short"
  </td>
</tr>
<tr>
  <td> <b>IntegerType</b> </td>
  <td> integer </td>
  <td>
  "integer"
  </td>
</tr>
<tr>
  <td> <b>LongType</b> </td>
  <td>
  integer <br />
  <b>Note:</b> Numbers will be converted to 8-byte signed integer numbers at runtime.
  Please make sure that numbers are within the range of
  -9223372036854775808 to 9223372036854775807.
  Otherwise, please convert data to decimal.Decimal and use DecimalType.
  </td>
  <td>
  "long"
  </td>
</tr>
<tr>
  <td> <b>FloatType</b> </td>
  <td>
  numeric <br />
  <b>Note:</b> Numbers will be converted to 4-byte single-precision floating
  point numbers at runtime.
  </td>
  <td>
  "float"
  </td>
</tr>
<tr>
  <td> <b>DoubleType</b> </td>
  <td> numeric </td>
  <td>
  "double"
  </td>
</tr>
<tr>
  <td> <b>DecimalType</b> </td>
  <td> Not supported </td>
  <td>
   Not supported
  </td>
</tr>
<tr>
  <td> <b>StringType</b> </td>
  <td> character </td>
  <td>
  "string"
  </td>
</tr>
<tr>
  <td> <b>BinaryType</b> </td>
  <td> raw </td>
  <td>
  "binary"
  </td>
</tr>
<tr>
  <td> <b>BooleanType</b> </td>
  <td> logical </td>
  <td>
  "bool"
  </td>
</tr>
<tr>
  <td> <b>TimestampType</b> </td>
  <td> POSIXct </td>
  <td>
  "timestamp"
  </td>
</tr>
<tr>
  <td> <b>DateType</b> </td>
  <td> Date </td>
  <td>
  "date"
  </td>
</tr>
<tr>
  <td> <b>ArrayType</b> </td>
  <td> vector or list </td>
  <td>
  list(type="array", elementType=<i>elementType</i>, containsNull=[<i>containsNull</i>])<br />
  <b>Note:</b> The default value of <i>containsNull</i> is <i>TRUE</i>.
  </td>
</tr>
<tr>
  <td> <b>MapType</b> </td>
  <td> environment </td>
  <td>
  list(type="map", keyType=<i>keyType</i>, valueType=<i>valueType</i>, valueContainsNull=[<i>valueContainsNull</i>])<br />
  <b>Note:</b> The default value of <i>valueContainsNull</i> is <i>TRUE</i>.
  </td>
</tr>
<tr>
  <td> <b>StructType</b> </td>
  <td> named list</td>
  <td>
  list(type="struct", fields=<i>fields</i>)<br />
  <b>Note:</b> <i>fields</i> is a Seq of StructFields. Also, two fields with the same
  name are not allowed.
  </td>
</tr>
<tr>
  <td> <b>StructField</b> </td>
  <td> The value type in R of the data type of this field
  (For example, integer for a StructField with the data type IntegerType) </td>
  <td>
  list(name=<i>name</i>, type=<i>dataType</i>, nullable=[<i>nullable</i>])<br />
  <b>Note:</b> The default value of <i>nullable</i> is <i>TRUE</i>.
  </td>
</tr>
</table>

</div>

</div>

## NaN Semantics

There is specially handling for not-a-number (NaN) when dealing with `float` or `double` types that
does not exactly match standard floating point semantics.
Specifically:

 - NaN = NaN returns true.
 - In aggregations, all NaN values are grouped together.
 - NaN is treated as a normal value in join keys.
 - NaN values go last when in ascending order, larger than any other numeric value.
 
 ## Arithmetic operations
 
Operations performed on numeric types (with the exception of `decimal`) are not checked for overflow.
This means that in case an operation causes an overflow, the result is the same that the same operation
returns in a Java/Scala program (eg. if the sum of 2 integers is higher than the maximum value representable,
the result is a negative number).
