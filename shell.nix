let
  baseconfig = { allowUnfree = true; };
  pkgs = import <nixpkgs> { config = baseconfig; };
  unstable = import <nixos-unstable> { config = baseconfig; };

  shell = pkgs.mkShell {
    packages = [
        pkgs.leiningen
        pkgs.openjdk25
        pkgs.k6
	    pkgs.jq
    ];

    shellHook = ''
        code .
    '';
    };
in shell
