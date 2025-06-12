declare module 'apexcharts' {
  export default class ApexCharts {
    constructor(element: HTMLElement, options: any);
    render(): Promise<void>;
    destroy(): void;
    updateOptions(options: any, redrawPaths?: boolean, animate?: boolean, updateSyncedCharts?: boolean): Promise<void>;
    updateSeries(newSeries: any, animate?: boolean): Promise<void>;
    appendSeries(newSeries: any, animate?: boolean): Promise<void>;
    toggleSeries(seriesName: string): any;
    showSeries(seriesName: string): void;
    hideSeries(seriesName: string): void;
    resetSeries(): void;
    zoomX(min: number, max: number): void;
    toggleDataPointSelection(seriesIndex: number, dataPointIndex: number): any;
    appendData(newData: any[]): void;
    addXaxisAnnotation(options: any, pushToMemory?: boolean, context?: any): void;
    addYaxisAnnotation(options: any, pushToMemory?: boolean, context?: any): void;
    addPointAnnotation(options: any, pushToMemory?: boolean, context?: any): void;
    removeAnnotation(id: string, options?: any): void;
    clearAnnotations(options?: any): void;
    dataURI(options?: any): Promise<void>;
  }
} 