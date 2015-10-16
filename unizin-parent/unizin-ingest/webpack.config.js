/* eslint no-var: [0] */
require('es6-promise').polyfill()
var path = require('path')
var webpack = require('webpack')
var ExtractTextPlugin = require("extract-text-webpack-plugin")
var extractText = new ExtractTextPlugin("contribute.css")

var devtool = 'source-map'
var env = {
    NODE_ENV: process.env.NODE_ENV
}
for (var key in env) {
    var value = (process.env[key] !== undefined ? process.env[key] : env[key])
    // Environment variables always come in as strings, so they may have to be
    // converted
    if (value === 'true') {
        value = true
    } else if (value === 'false') {
        value = false
    } else if (!isNaN(parseInt(value, 10))) {
        value = parseInt(value, 10)
    }

    env[key] = JSON.stringify(value)
}

var css = '[path][name]---[local]---[hash:base64:5]'
var optionalPlugins = []
if (process.env.NODE_ENV === 'production') {
    css = '[hash:base64]'
    devtool = 'cheap-source-map'
    optionalPlugins.push(
        new webpack.optimize.UglifyJsPlugin({
            sourceMap: false,
            compress: { warnings: false }
        })
    )
}


module.exports = {
    context: path.join(__dirname, 'src', 'main', 'js'),
    devtool: devtool,
    entry: {
        contribute: "./main.js",
    },
    // https://github.com/webpack/webpack/issues/811#issuecomment-75451797
    resolve: {
        extensions: ['', '.js', '.jsx'],
        fallback: path.join(__dirname, "node_modules")
    },
    resolveLoader: {
        fallback: path.join(__dirname, "node_modules")
    },
    module: {
        loaders: [
            {
                test: /\.css$/,
                loader: extractText.extract(
                    'css-loader?localIdentName=' + css
                )
            },
            {
                test   : /\.scss$/,
                loader: extractText.extract([
                    'css-loader?sourceMap&localIdentName=' + css,
                    'resolve-url',
                    'sass?sourceMap'
                ].join("!"))
            },
            {
                test: /\.jsx?$/,
                exclude: /(node_modules|bower_components)/,
                loader: 'babel',
                query: {
                    cacheDirectory: true,
                }
            }
        ]
    },
    plugins: [
        new webpack.DefinePlugin({
            'process.env': env
        }),
        new webpack.NoErrorsPlugin(),
        extractText
    ].concat(optionalPlugins),
    output: {
        path: path.join(__dirname, 'target', 'classes', 'skin', 'resources'),
        filename: '[name].js',
        sourceMapFilename: "[hash].[file].map",
        chunkFilename: '[hash].[id].engage.js',
    },
    devServer: {
        // contentBase: 'dist/release/',
        port: 9595,
        inline: true,
    },
}
