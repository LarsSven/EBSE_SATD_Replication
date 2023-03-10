/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

$(document).ready(function() {
	$.ajax({ url : "setupInfo?get=globalC", type : "GET", cache: false, success : function(json) {
		loadConfigTable(json);
	}, dataType : "json",
	});
});

/*
 * Initializes global config table
 */
function loadConfigTable(json) {
	$("#confTable1").empty();
	var table = "<table class=\"table table-bordered table-hover table-striped\">";
	table += "<tr><th class=\"col-lg-4\">Property</th><th>Value</th></tr>";
	for (var key in json.user) {
		if (json.user.hasOwnProperty(key)) {
			table += "<tr><td>"+key+"</td><td>"+json.user[key]+"</td></tr>";
		}
	}
	table += "</table>";
	$("#confTable1").append(table);

	$("#confTable2").empty();
	var table = "<table class=\"table table-bordered table-hover table-striped\">";
	table += "<tr><th class=\"col-lg-4\">Property</th><th>Value</th></tr>";
	for (var key in json.default) {
		if (json.default.hasOwnProperty(key)) {
			table += "<tr><td>"+key+"</td><td>"+json.default[key]+"</td></tr>";
		}
	}
	table += "</table>";
	$("#confTable2").append(table);
}
