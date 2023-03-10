# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

#' @include R6.R

#' @include R6.R
#'
#' @title class arrow::json::TableReader
#'
#' @usage NULL
#' @format NULL
#' @docType class
#'
#' @section Methods:
#'
#' - `Read()` : read the json file as an [arrow::Table][arrow__Table]
#'
#' @rdname arrow__json__TableReader
#' @name arrow__json__TableReader
`arrow::json::TableReader` <- R6Class("arrow::json::TableReader", inherit = `arrow::Object`,
  public = list(
    Read = function() shared_ptr(`arrow::Table`, json___TableReader__Read(self))
  )
)

`arrow::json::ReadOptions` <- R6Class("arrow::json::ReadOptions", inherit = `arrow::Object`)
`arrow::json::ParseOptions` <- R6Class("arrow::json::ParseOptions", inherit = `arrow::Object`)

#' read options for the json reader
#'
#' @param use_threads Whether to use the global CPU thread pool
#' @param block_size Block size we request from the IO layer; also determines the size of chunks when use_threads is `TRUE`. NB: if false, input must end with an empty line
#'
#' @export
json_read_options <- function(use_threads = TRUE, block_size = 1048576L) {
  shared_ptr(`arrow::json::ReadOptions`, json___ReadOptions__initialize(
    list(
      use_threads = use_threads,
      block_size = block_size
    )
  ))
}

#' Parsing options
#'
#' @param newlines_in_values Whether objects may be printed across multiple lines (for example pretty printed),
#'
#' @export
json_parse_options <- function(
  newlines_in_values = FALSE
){
  shared_ptr(`arrow::json::ParseOptions`, json___ParseOptions__initialize(
    list(
      newlines_in_values = newlines_in_values
    )
  ))
}

#' Json table reader
#'
#' @param file file
#' @param read_options, see [json_read_options()]
#' @param parse_options, see [json_parse_options()]
#' @param ... additional parameters.
#'
#' @export
json_table_reader <- function(file,
  read_options = json_read_options(),
  parse_options = json_parse_options(),
  ...
){
  UseMethod("json_table_reader")
}

#' @importFrom rlang abort
#' @export
json_table_reader.default <- function(file,
  read_options = json_read_options(),
  parse_options = json_parse_options(),
  ...
) {
  abort("unsupported")
}

#' @export
`json_table_reader.character` <- function(file,
  read_options = json_read_options(),
  parse_options = json_parse_options(),
  ...
){
  json_table_reader(fs::path_abs(file),
    read_options = read_options,
    parse_options = parse_options,
    ...
  )
}

#' @export
`json_table_reader.fs_path` <- function(file,
  read_options = json_read_options(),
  parse_options = json_parse_options(),
  ...
){
  json_table_reader(ReadableFile(file),
    read_options = read_options,
    parse_options = parse_options,
    ...
  )
}

#' @export
`json_table_reader.arrow::io::InputStream` <- function(file,
  read_options = json_read_options(),
  parse_options = json_parse_options(),
  ...
){
  shared_ptr(`arrow::json::TableReader`,
    json___TableReader__Make(file, read_options, parse_options)
  )
}

#' @export
`json_table_reader.arrow::json::TableReader` <- function(file,
  read_options = json_read_options(),
  parse_options = json_parse_options(),
  ...
){
  file
}

#' Read json file into an arrow::Table
#'
#' Use [arrow::json::TableReader][arrow__json__TableReader] from [json_table_reader()]
#'
#' @param ... Used to construct an arrow::json::TableReader
#' @param as_tibble convert the [arrow::Table][arrow__Table] to a data frame
#'
#' @export
read_json_arrow <- function(..., as_tibble = TRUE) {
  tab <- json_table_reader(...)$Read()
  if (isTRUE(as_tibble)) {
    tab <- as_tibble(tab)
  }
  tab
}
