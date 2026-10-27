using {DataInspectorService} from './DataInspectorService';

annotate DataInspectorService with @(restrict: [{
    grant: ['READ'],
    to   : 'capDataInspectorReadonly'
}]);
