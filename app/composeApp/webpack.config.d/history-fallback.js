// Deep links (GLB-110): the development server answers every route with the app shell, as the
// production hosting does.
if (config.devServer) {
    config.devServer.historyApiFallback = true;
}
