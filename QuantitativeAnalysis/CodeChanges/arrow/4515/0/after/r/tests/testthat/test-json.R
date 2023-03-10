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

context("arrow::json::TableReader")

test_that("Can read json file with scalars columns (ARROW-5503)", {
  tf <- tempfile()
  writeLines('
    { "hello": 3.5, "world": false, "yo": "thing" }
    { "hello": 3.25, "world": null }
    { "hello": 3.125, "world": null, "yo": "\u5fcd" }
    { "hello": 0.0, "world": true, "yo": null }
  ', tf)

  tab1 <- read_json_arrow(tf, as_tibble = FALSE)
  tab2 <- read_json_arrow(mmap_open(tf), as_tibble = FALSE)
  tab3 <- read_json_arrow(ReadableFile(tf), as_tibble = FALSE)

  expect_equal(tab1, tab2)
  expect_equal(tab1, tab3)

  expect_equal(
    tab1$schema,
    schema(hello = float64(), world = boolean(), yo = utf8())
  )
  tib <- as.data.frame(tab1)
  expect_equal(tib$hello, c(3.5, 3.25, 3.125, 0))
  expect_equal(tib$world, c(FALSE, NA, NA, TRUE))
  expect_equal(tib$yo, c("thing", NA, "\u5fcd", NA))

  unlink(tf)
})

test_that("read_json_arrow() converts to tibble", {
  tf <- tempfile()
  writeLines('
    { "hello": 3.5, "world": false, "yo": "thing" }
    { "hello": 3.25, "world": null }
    { "hello": 3.125, "world": null, "yo": "\u5fcd" }
    { "hello": 0.0, "world": true, "yo": null }
  ', tf)

  tab1 <- read_json_arrow(tf)
  tab2 <- read_json_arrow(mmap_open(tf))
  tab3 <- read_json_arrow(ReadableFile(tf))

  expect_is(tab1, "tbl_df")
  expect_is(tab2, "tbl_df")
  expect_is(tab3, "tbl_df")

  expect_equal(tab1, tab2)
  expect_equal(tab1, tab3)

  expect_equal(tab1$hello, c(3.5, 3.25, 3.125, 0))
  expect_equal(tab1$world, c(FALSE, NA, NA, TRUE))
  expect_equal(tab1$yo, c("thing", NA, "\u5fcd", NA))

  unlink(tf)
})

test_that("Can read json file with nested columns (ARROW-5503)", {
  tf <- tempfile()
  writeLines('
    { "hello": 3.5, "world": false, "yo": "thing", "arr": [1, 2, 3], "nuf": {} }
    { "hello": 3.25, "world": null, "arr": [2], "nuf": null }
    { "hello": 3.125, "world": null, "yo": "\u5fcd", "arr": [], "nuf": { "ps": 78 } }
    { "hello": 0.0, "world": true, "yo": null, "arr": null, "nuf": { "ps": 90 } }
  ', tf)

  tab1 <- read_json_arrow(tf, as_tibble = FALSE)
  tab2 <- read_json_arrow(mmap_open(tf), as_tibble = FALSE)
  tab3 <- read_json_arrow(ReadableFile(tf), as_tibble = FALSE)

  expect_equal(tab1, tab2)
  expect_equal(tab1, tab3)

  expect_equal(
    tab1$schema,
    schema(
      hello = float64(),
      world = boolean(),
      yo = utf8(),
      arr = list_of(int64()),
      nuf = struct(ps = int64())
    )
  )
  # cannot yet test list and struct types in R api
  # tib <- as.data.frame(tab1)

  unlink(tf)
})

