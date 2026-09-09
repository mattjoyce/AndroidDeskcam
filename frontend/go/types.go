package main

// Kind is where a parameter takes effect. This is decision D9 in the specification.
type Kind int

const (
	// Camera state persists on the phone until something changes it again.
	Camera Kind = iota
	// Presentation applies to the one request that names it, then is forgotten.
	Presentation
	// Router parameters are read by the transport, never by the settings.
	Router
)

func (k Kind) String() string {
	switch k {
	case Camera:
		return "camera state"
	case Presentation:
		return "presentation"
	default:
		return "router"
	}
}
