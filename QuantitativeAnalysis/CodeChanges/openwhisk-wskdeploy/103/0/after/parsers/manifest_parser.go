package parsers

import (
	"log"
	"os"
	"path"
	"strings"

	"github.com/openwhisk/openwhisk-client-go/whisk"
	"github.com/openwhisk/openwhisk-wskdeploy/utils"
	"gopkg.in/yaml.v2"
)

func (dm *YAMLParser) Unmarshal(input []byte, manifest *ManifestYAML) error {
	err := yaml.Unmarshal(input, manifest)
	if err != nil {
		log.Fatalf("error happened during unmarshal :%v", err)
		return err
	}
	return nil
}

func (dm *YAMLParser) Marshal(manifest *ManifestYAML) (output []byte, err error) {
	data, err := yaml.Marshal(manifest)
	if err != nil {
		log.Fatalf("err happened during marshal :%v", err)
		return nil, err
	}
	return data, nil
}

func (dm *YAMLParser) ParseManifest(mani string) *ManifestYAML {
	mm := NewYAMLParser()
	maniyaml := ManifestYAML{}
	content, err := new(utils.ContentReader).LocalReader.ReadLocal(mani)
	utils.Check(err)
	err = mm.Unmarshal(content, &maniyaml)
	utils.Check(err)
	maniyaml.Filepath = mani
	return &maniyaml
}

// Is we consider multi pacakge in one yaml?
func (dm *YAMLParser) ComposePackage(mani *ManifestYAML) (*whisk.SentPackageNoPublish, error) {
	//mani := dm.ParseManifest(manipath)
	pag := &whisk.SentPackageNoPublish{}
	pag.Name = mani.Package.Packagename
	//The namespace for this package is absent, so we use default guest here.
	pag.Namespace = mani.Package.Namespace
	pag.Publish = false
	return pag, nil
}

func (dm *YAMLParser) ComposeSequences(namespace string, packageName string, mani *ManifestYAML) ([]utils.ActionRecord, error) {
	var s1 []utils.ActionRecord = make([]utils.ActionRecord, 0)
	for key, sequence := range mani.Package.Sequences {
		wskaction := new(whisk.Action)
		wskaction.Exec = new(whisk.Exec)
		wskaction.Exec.Kind = "sequence"
		actionList := strings.Split(sequence.Actions, ",")

		var components []string
		for _, a := range actionList {

			act := strings.TrimSpace(a)

			if !strings.HasPrefix(act, packageName+"/") {
				act = path.Join(packageName, act)
			}
			components = append(components, path.Join("/"+namespace, act))
		}

		wskaction.Exec.Components = components
		wskaction.Name = key
		wskaction.Publish = false
		wskaction.Namespace = namespace

		record := utils.ActionRecord{wskaction, packageName, key}
		s1 = append(s1, record)
	}
	return s1, nil
}

func (dm *YAMLParser) ComposeActions(mani *ManifestYAML, manipath string) ([]utils.ActionRecord, error) {

	var s1 []utils.ActionRecord = make([]utils.ActionRecord, 0)
	for key, action := range mani.Package.Actions {
		splitmanipath := strings.Split(manipath, string(os.PathSeparator))
		filePath := strings.TrimRight(manipath, splitmanipath[len(splitmanipath)-1]) + action.Location
		dat, err := new(utils.ContentReader).LocalReader.ReadLocal(filePath)
		utils.Check(err)

		wskaction := new(whisk.Action)
		wskaction.Exec = new(whisk.Exec)
		wskaction.Exec.Code = string(dat)
		wskaction.Exec = new(whisk.Exec)
		wskaction.Exec.Code = string(dat)

		if action.Runtime != "" {
			wskaction.Exec.Kind = action.Runtime
		} else if action.Location != "" {

			ext := path.Ext(filePath)
			kind := "nodejs:default"

			switch ext {
			case ".swift":
				kind = "swift:default"
			case ".js":
				kind = "nodejs:default"
			case ".py":
				kind = "python"
			}

			wskaction.Exec.Kind = kind
		}

		wskaction.Name = key
		wskaction.Publish = false

		record := utils.ActionRecord{wskaction, "", action.Location}
		s1 = append(s1, record)
	}

	return s1, nil

}

func (dm *YAMLParser) ComposeTriggers(manifest *ManifestYAML) ([]*whisk.Trigger, error) {

	var t1 []*whisk.Trigger = make([]*whisk.Trigger, 0)
	pkg := manifest.Package
	for _, trigger := range pkg.GetTriggerList() {
		wsktrigger := new(whisk.Trigger)
		wsktrigger.Name = trigger.Name
		wsktrigger.Namespace = trigger.Namespace
		wsktrigger.Publish = false
		t1 = append(t1, wsktrigger)
	}
	return t1, nil
}

func (dm *YAMLParser) ComposeRules(manifest *ManifestYAML) ([]*whisk.Rule, error) {

	var r1 []*whisk.Rule = make([]*whisk.Rule, 0)
	pkg := manifest.Package
	for _, rule := range pkg.GetRuleList() {
		wskrule := rule.ComposeWskRule()
		r1 = append(r1, wskrule)
	}

	return r1, nil
}

func (action *Action) ComposeWskAction(manipath string) (*whisk.Action, error) {
	wskaction, err := utils.CreateActionFromFile(manipath, action.Location)
	utils.Check(err)
	wskaction.Name = action.Name
	wskaction.Version = action.Version
	wskaction.Namespace = action.Namespace
	return wskaction, err
}
