/*
 *
 *  qReport - A Groovy script to run tests and generate reports in SoapUI
 *  Copyright (C) 2022-2023 Lily Hopkins
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

import com.eviware.soapui.model.testsuite.TestStepResult.TestStepStatus

// Inital file locations
def dataFile = testRunner.testCase.getPropertyValue("_input")
def reportName = testRunner.testCase.getPropertyValue("_output")
def groovyUtils = new com.eviware.soapui.support.GroovyUtils(context)
def projectDir = new File(groovyUtils.projectPath)

// HTML Templates
def headTemplate = """<!doctype html><html><head><meta charset="utf-8" /><title>Test Run Output</title><style type="text/css">
body { font-family: sans-serif; font-weight: 300; }
table { border-spacing: 0; border-collapse: collapse; }
td, th { vertical-align: top; border: 1px solid #c0c0c0; padding: 2px; }
@media screen {
  .hidden { display: none; }
  pre, code { max-width: 700px; overflow: scroll; }
}
.success { background-color: #aaffaa; border-color: #c0ffc0; }
.discrepancy { background-color: #ffaaaa; border-color: #ffc0c0; }
@media print {
  [data-toggles] {
	display: none;
  }
}
</style></head><body><h1>Test Run Report</h1><p><small>Generated at %TIME% by <a href="https://github.com/lilopkins/SoapUI-qReport" target="_blank">qReport</a></small></p><table>
<thead><tr><th>Test Case</th><th>Raw Request</th><th>Raw Response</th><th>Assertions</th><th>Notes</th></tr></thead><tbody>
"""
def testTemplate = """<tr class="%ROW_STATUS%"><td><a href="#%IDNS%" name="%IDNS%">%ID%</a></td><td><button data-toggles="%IDNS%-req">View/Hide</button><pre id="%IDNS%-req" class="hidden"><code>%REQ%
</code></pre></td><td><button data-toggles="%IDNS%-res">View/Hide</button><pre id="%IDNS%-res" class="hidden"><code>%RES%
</code></pre></td><td>%ASSERTION_STATUS%</td><td>%NOTES%</td></tr>
"""
def footTemplate = """</tbody></table><p>%PASS% tests passed, %FAIL% tests failed, out of %TOTAL% tests</p><script type="text/javascript">
document.querySelectorAll("[data-toggles]").forEach(el => el.addEventListener("click", ev => document.getElementById(el.dataset["toggles"]).classList.toggle("hidden")))
</script></body></html>
"""

def parseCSV(csvString) {
  def rows = []
  def inQuotes = false
  def numQuotes = 0
  def buffer = new StringBuilder()
  def row = []

  csvString.each { ch ->
    if (ch == "\"") {
      inQuotes = !inQuotes
      numQuotes += 1
      if (numQuotes == 2) {
        numQuotes = 0
        buffer.append(ch)
      }
    } else if (ch == "," && !inQuotes) {
      numQuotes = 0
      row.add(buffer.toString().trim())
      buffer = new StringBuilder()
    } else if (ch == "\n" && !inQuotes) {
      numQuotes = 0
      row.add(buffer.toString().trim())
      buffer = new StringBuilder()
      rows.add(row)
      row = []
    } else {
      numQuotes = 0
      buffer.append(ch)
    }
  }

  return rows
}

// Runner variables
def headers = []
def headersPopulated = false
def file = new File(projectDir, dataFile)
def testRunTime = new Date()
def reportFile = new File(projectDir, reportName + "-report-" + testRunTime.format("yyyy-MM-dd-HH-mm-ss") + ".html")
def report = headTemplate.replaceAll("%TIME%", testRunTime.toString())
def totalTests = 0
def passedTests = 0
def failedTests = 0

log.info("Loading data from file: " + dataFile)
def parsedCsv = parseCSV(file.getText())

parsedCsv.each { row ->
  if (headersPopulated) {
    row.eachWithIndex{ it, i -> testRunner.testCase.setPropertyValue(headers[i], it) }
    
    def caseId = testRunner.testCase.getPropertyValue("ID")
    def caseIdNoSpaces = caseId.replaceAll(" ", "_")
    def rowStatus = ""
    log.info("Test case: " + caseId)
    def stepName = testRunner.testCase.getPropertyValue("_request_step")
    def step = testRunner.testCase.getTestStepByName(stepName)
    def stepResult = testRunner.runTestStepByName(stepName)
    def rawReq = "Request failed."
    def rawRes = "Request failed."
    def statusTxt = "---"
    if (stepResult.response == null) {
        log.warn(caseId + " failed (request didn't respond with content)!")
        rowStatus = "discrepancy"
        statusTxt = "Request failed."
        failedTests += 1
    } else {
      rawReq = new String(stepResult.getRawRequestData()).replaceAll("<", "&lt;").replaceAll(">", "&gt;")
      rawRes = new String(stepResult.getRawResponseData()).replaceAll("<", "&lt;").replaceAll(">", "&gt;")
      def status = stepResult.getStatus()
      switch (status) {
        case TestStepStatus.OK:
          log.info(caseId + " passed!")
          rowStatus = "success"
          passedTests += 1
          break
        case TestStepStatus.FAILED:
          log.warn(caseId + " failed!")
          rowStatus = "discrepancy"
          failedTests += 1
          break
        case TestStepStatus.UNKNOWN:
          log.warn(caseId + " has no assertions!")
          statusTxt = "No assertions!"
          break
        case TestStepStatus.CANCELLED:
          def cancelled = false
          assert cancelled
          break
      }

      // Get assertion data
      if (step.metaClass.respondsTo(step, "getAssertionList")) {
        def assertionsList = step.getAssertionList()
        statusTxt = assertionsList.size() + " assertion/s:"
        assertionsList.each {
          statusTxt += "<br />$it.label &ndash; $it.status"
          if (it.errors != null) {
            statusTxt += "<br />" + (" &rarr; $it.errors".replaceAll("<", "&lt;").replaceAll(">", "&gt;"))
          }
        }
      }
    }
    
    totalTests += 1
    def notesRaw = testRunner.testCase.getPropertyValue("_notes")
    def notes = notesRaw == null ? "" : notesRaw
    report += testTemplate.replaceAll("%ID%", caseId).replaceAll("%IDNS%", caseIdNoSpaces).replaceAll("%REQ%", rawReq).replaceAll("%RES%", rawRes).replaceAll("%ASSERTION_STATUS%", java.util.regex.Matcher.quoteReplacement(statusTxt)).replaceAll("%ROW_STATUS%", rowStatus).replaceAll("%NOTES%", notes)
  } else {
    row.each{ headers << it }
    headersPopulated = true
  }
}

report += footTemplate.replaceAll("%PASS%", passedTests.toString()).replaceAll("%FAIL%", failedTests.toString()).replaceAll("%TOTAL%", totalTests.toString())
reportFile.withWriter { out ->
  out.println(report)
}

log.info("Written report to: " + reportFile.getPath())
