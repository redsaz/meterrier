<#--
 Copyright 2018 Redsaz <redsaz@gmail.com>.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
-->
<#escape x as x?html>
      <div class="container">
        <h2>${review.name}</h2>
        <div>
          <#noescape>${descriptionHtml}</#noescape>
        </div>
        <h3>Applicable Logs</h3>
        <div class="table-responsive">
          <table class="table table-striped">
            <thead>
              <tr>
                <th>Name</th>
                <th>Notes</th>
              </tr>
            </thead>
            <tbody>
              <#list briefs as brief>
              <tr>
                <td><a href="${base}/logs/${brief.id}/${brief.uriName}">${brief.name}</a></td>
                <td><a href="${base}/logs/${brief.id}/${brief.uriName}">${brief.notes}</a></td>
              </tr>
              </#list>
            </tbody>
          </table>
        </div>
      </div>
      <div class="container">
        <ul class="nav nav-tabs" role="tablist">
          <li role="presentation" class="nav-item"><a class="graph-nav nav-link" href="#custom" aria-controls="custom" role="tab" data-toggle="tab" onclick="switchActiveNav('graph-nav', this)">custom</a></li>
        <#list reviewGraphs as g>
          <li role="presentation" class="nav-item"><a class="graph-nav nav-link<#if g?is_first> active</#if>" href="#${g.urlName}" aria-controls="${g.name}" role="tab" data-toggle="tab" onclick="switchActiveNav('graph-nav', this)">${g.name}</a></li>
        </#list>
        </ul>
        <div class="tab-content" style="width: 100%">
          <div role="tabpanel" class="tab-pane" id="custom" style="width: 100%">
            <h2>Custom</h2>
            <div class="graph" style="width: 100%">
              <img src="${custom}"/>
            </div>
          </div>
        <#list reviewGraphs as g>
          <div role="tabpanel" class="tab-pane <#if g?is_first>active</#if>" id="${g.urlName}" style="width: 100%">
            <h2>${g.name}</h2>
            <div class="graph" style="width: 100%">
              <div class="ct-chart" id="graphdiv${g?index}" style="width: 100%;<#if g.height??> height: ${g.height}</#if>"></div>
            </div>
          </div>
        </#list>
        </div>
      </div>
      <script src="${dist}/js/chartist.min.js"></script>
      <script src="${dist}/js/chartist-plugin-tooltip.min.js"></script>
      <script src="${dist}/js/chartist-plugin-legend.min.js"></script>
      <script src="${dist}/js/dygraph.min.js"></script>
      <#list reviewGraphs as g>
        <script>
          <#noescape>${g.chartHtml}</#noescape>
        </script>
      </#list>
</#escape>
