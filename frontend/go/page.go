package main

import _ "embed"

// The console's page. Kept as a file rather than a quoted string so it stays editable and
// so nothing has to be escaped twice.
//
//go:embed page.html
var consolePage string
