package main

// version is this build's semver. The one source is VERSION at the top of the repository.
// go:embed cannot reach outside the module and a plain `go build` must still print the
// right number, so the number is copied here and TestTheVersionIsTheRepositorysVersion
// holds the copy to the source.
const version = "0.2.0"
