<!DOCTYPE html>
<html>
<head>
    <%
        String neonServerUrl = getServletContext().getInitParameter("neon.url");
        String owfServerUrl = getServletContext().getInitParameter("owf.url");
    %>

    <title>Aperture Map Display</title>

    <link rel="stylesheet" type="text/css" href="css/bootstrap/bootstrap.min.css">
    <link rel="stylesheet" type="text/css" href="css/map.css">
    <link rel="stylesheet" type="text/css" href="<%=neonServerUrl%>/css/neon.css">

    <script src="<%=owfServerUrl%>/js/owf-widget.js"></script>
    <script src="<%=neonServerUrl%>/js/neon.js"></script>

    <!-- build:js js/openlayers-map.js -->
    <script src="d3/d3.v3.min.js"></script>
    <script src="openlayers/OpenLayers.js"></script>
    <script src="heatmap/heatmap.js"></script>
    <script src="heatmap/heatmap-openlayers.js"></script>
    <script src="mapwidget.js"></script>
    <!-- endbuild -->


    <script>
        OWF.relayFile = 'js/eventing/rpc_relay.uncompressed.html';
        neon.query.SERVER_URL = '<%=neonServerUrl%>';
        neon.util.AjaxUtils.useDefaultStartStopCallbacks();
    </script>

</head>
<body>

<div class="container">


    <div class="controls-row">
        <div class="control-group">
            <label class="control-label" for="latitude">Latitude Field</label>

            <div class="controls">
                <select class="dropdown"></select>
            </div>
        </div>

        <div class="control-group">
            <label class="control-label" for="longitude">Longitude Field</label>

            <div class="controls">
                <select class="dropdown"></select>
            </div>
        </div>
        <div class="control-group">
            <label class="control-label" for="size-by">Size By</label>

            <div class="controls">
                <select class="dropdown"></select>
            </div>
        </div>


        <div class="control-group">
            <label class="control-label" for="color-by">Color By</label>

            <div class="controls">
                <select class="dropdown"></select>
            </div>
        </div>
    </div>


    <div id="map"></div>


</div>


</body>
</html>