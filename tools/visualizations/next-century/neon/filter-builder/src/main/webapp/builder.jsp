<%@page contentType="text/html;charset=UTF-8" %>
<%@page pageEncoding="UTF-8" %>
<%@ page session="false" %>

<!DOCTYPE html>
<html>
<head>
    <title>Query Builder</title>

    <%
        String neonServerUrl = getServletContext().getInitParameter("neon.url");
        String owfServerUrl = getServletContext().getInitParameter("owf.url");
    %>

    <script src="<%=owfServerUrl%>/js/owf-widget.js"></script>
    <script src="<%=neonServerUrl%>/js/neon.js"></script>

    <script>
        neon.query.SERVER_URL = '<%=neonServerUrl%>';
        neon.util.AjaxUtils.useDefaultStartStopCallbacks();

        neon.util.AjaxUtils.doGet(neon.query.SERVER_URL + "/services/languageservice/datastores", {
            success : function(data) {
                data.forEach(function(datastoreName) {
                    datastoreOption = document.createElement("option");
                    datastoreOption.text = datastoreName;
                    datastoreOption.setAttribute("value", datastoreName);
                    $("#datastoreSelect").append(datastoreOption);
                })
            }
        });
    </script>
    <link rel="stylesheet" type="text/css" href="<%=neonServerUrl%>/css/neon.css" />

</head>
<body>
    <h4>Select a Datastore</h4>
    <select id="datastoreSelect">
    </select>
    <h4>
    Enter a Query
    </h4>
    <textarea id="queryText" cols="80" rows="3"></textarea>
    <br/><br/>
    <button id="submit" onclick="submitter();">Submit</button>
    <br/><br/>
    <div id="resp"></div>

    <script>
        function submitter(){
            var query = $('#queryText').val();
            var datastore = $('#datastoreSelect').val();

            neon.util.AjaxUtils.doPost(neon.query.SERVER_URL + "/services/languageservice/query",
                    {
                        data: { text: query, datastore: datastore },
                        success : function(data){
                            $('#resp').html(JSON.stringify(data));
                    }
            });
        }
    </script>

</body>
</html>