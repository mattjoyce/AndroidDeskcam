// Package panel embeds the same camera page that Android packages as an asset.
package panel

import _ "embed"

//go:embed assets/panel.html
var HTML string
