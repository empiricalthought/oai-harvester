import styles from './search_result.scss'
import React from 'react'
import { Link } from 'react-router'
import { routeResult, routeReturnUrl } from '../actions/route.js'
import Cover from './cover.jsx'
import Pager from '../components/pager.jsx'
import Loading from './loading.jsx'

export default class SearchResults extends React.Component {
    static displayName = 'SearchResults'

    constructor(props, context) {
        super(props, context)

        this.onPage = this.onPage.bind(this)
        this.renderResult = this.renderResult.bind(this)
    }

    renderResult(result, index) {
        const { title } = result
        const returnUrl = routeReturnUrl(result).url
        const resultRoute = routeResult(result).route
        const insertLabel = "Insert " + title + " into your page"

        return (
            <li key={result.uid} className={styles.result}>

                <Cover className={styles.cover} document={result} />

                <Link to={resultRoute}>
                    {title}
                </Link>

                <ul className={styles.metadata}>
                  <li>Type: {result.type}</li>
                  <li>Entity: {result['entity-type']}</li>
                </ul>

                <a href={returnUrl} className={styles.btn} aria-label={insertLabel}>
                    + Insert
                </a>
            </li>
        )
    }

    onPage(page) {
        const { criteria, searchFor } = this.props
        searchFor(criteria.text, page)
    }

    render() {
        const { criteria, results } = this.props

        if (results.totalSize == null) {
            return <Loading message={`Loading Results for '${criteria.text}'`} />
        }

        const { totalSize = 0, pageSize = 20 } = results
        const resultsString = `${totalSize} Results for '${criteria.text}'`

        return (
            <div className={styles.results}>
                <h1>{resultsString}</h1>

                <Pager
                    current={this.props.page}
                    max={Math.ceil(totalSize / pageSize)}
                    onChange={this.onPage}
                    ariaLabel="Results pagination top" />

                <ul>
                    {results.entries.map(this.renderResult)}
                </ul>

                <Pager
                    current={this.props.page}
                    max={Math.ceil(totalSize / pageSize)}
                    onChange={this.onPage}
                    ariaLabel="Results pagination bottom" />
              </div>
        )
    }
}
